/*
 * Copyright 2014-2016 Groupon, Inc
 * Copyright 2014-2016 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.invoice.usage;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.catalog.api.BillingMode;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.Usage;
import org.killbill.billing.catalog.api.UsageType;
import org.killbill.billing.invoice.api.InvoiceApiException;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.generator.InvoiceItemGenerator.InvoiceItemGeneratorLogger;
import org.killbill.billing.invoice.generator.InvoiceWithMetadata.TrackingRecordId;
import org.killbill.billing.invoice.usage.ContiguousIntervalUsageInArrear.UsageInArrearItemsAndNextNotificationDate;
import org.killbill.billing.junction.BillingEvent;
import org.killbill.billing.usage.RawUsage;
import org.killbill.billing.util.config.definition.InvoiceConfig;
import org.killbill.billing.util.config.definition.InvoiceConfig.UsageDetailMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

/**
 * There is one such class created for each subscriptionId referenced in the billingEvents.
 */
public class SubscriptionUsageInArrear {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionUsageInArrear.class);

    private static final Comparator<RawUsage> RAW_USAGE_DATE_COMPARATOR = new Comparator<RawUsage>() {
        @Override
        public int compare(final RawUsage o1, final RawUsage o2) {
            int compared = o1.getDate().compareTo(o2.getDate());
            if (compared != 0) {
                return compared;
            } else {
                compared = o1.getUnitType().compareTo(o2.getUnitType());
                if (compared != 0) {
                    return compared;
                } else {
                    return o1.hashCode() != o2.hashCode() ? o1.hashCode() - o2.hashCode() : 0;
                }
            }
        }
    };

    private final UUID accountId;
    private final UUID invoiceId;
    private final List<BillingEvent> subscriptionBillingEvents;
    private final LocalDate targetDate;
    private final List<RawUsage> rawSubscriptionUsage;
    private final Set<TrackingRecordId> existingTrackingIds;
    private final LocalDate rawUsageStartDate;
    private final InternalTenantContext internalTenantContext;
    private final UsageDetailMode usageDetailMode;
    private final InvoiceConfig invoiceConfig;

    public SubscriptionUsageInArrear(final UUID accountId,
                                     final UUID invoiceId,
                                     final List<BillingEvent> subscriptionBillingEvents,
                                     final List<RawUsage> rawUsage,
                                     final Set<TrackingRecordId> existingTrackingIds,
                                     final LocalDate targetDate,
                                     final LocalDate rawUsageStartDate,
                                     final UsageDetailMode usageDetailMode,
                                     final InvoiceConfig invoiceConfig,
                                     final InternalTenantContext internalTenantContext) {

        this.accountId = accountId;
        this.invoiceId = invoiceId;
        this.subscriptionBillingEvents = subscriptionBillingEvents;
        this.targetDate = targetDate;
        this.rawUsageStartDate = rawUsageStartDate;
        this.internalTenantContext = internalTenantContext;
        // Extract raw usage for that subscription and sort it by date
        this.rawSubscriptionUsage = Ordering.<RawUsage>from(RAW_USAGE_DATE_COMPARATOR).sortedCopy(Iterables.filter(rawUsage, new Predicate<RawUsage>() {
            @Override
            public boolean apply(final RawUsage input) {
                return input.getSubscriptionId().equals(subscriptionBillingEvents.get(0).getSubscriptionId());
            }
        }));
        this.existingTrackingIds = existingTrackingIds;
        this.usageDetailMode = usageDetailMode;
        this.invoiceConfig = invoiceConfig;
    }

    /**
     * Based on billing events, (@code existingUsage} and targetDate, figure out what remains to be billed.
     *
     * @param existingUsage the existing on disk usage items.
     * @throws CatalogApiException
     */
    public SubscriptionUsageInArrearItemsAndNextNotificationDate computeMissingUsageInvoiceItems(final List<InvoiceItem> existingUsage, final InvoiceItemGeneratorLogger invoiceItemGeneratorLogger) throws CatalogApiException, InvoiceApiException {
        final SubscriptionUsageInArrearItemsAndNextNotificationDate result = new SubscriptionUsageInArrearItemsAndNextNotificationDate();
        final List<ContiguousIntervalUsageInArrear> billingEventTransitionTimePeriods = computeInArrearUsageInterval();
        for (final ContiguousIntervalUsageInArrear usageInterval : billingEventTransitionTimePeriods) {
            final UsageInArrearItemsAndNextNotificationDate newItemsWithDetailsAndDate = usageInterval.computeMissingItemsAndNextNotificationDate(existingUsage);

            // For debugging purposes
            invoiceItemGeneratorLogger.append(usageInterval, newItemsWithDetailsAndDate.getInvoiceItems());

            result.addUsageInArrearItemsAndNextNotificationDate(usageInterval.getUsage().getName(), newItemsWithDetailsAndDate);
            result.addTrackingIds(newItemsWithDetailsAndDate.getTrackingIds());
        }
        return result;
    }

    @VisibleForTesting
    List<ContiguousIntervalUsageInArrear> computeInArrearUsageInterval() throws CatalogApiException, InvoiceApiException {
        final List<ContiguousIntervalUsageInArrear> usageIntervals = Lists.newLinkedList();

        final Map<UsageKey, ContiguousIntervalUsageInArrear> inFlightInArrearUsageIntervals = new HashMap<UsageKey, ContiguousIntervalUsageInArrear>();

        final Set<UsageKey> allSeenUsage = new HashSet<UsageKey>();

        Set<String> allSeenUnitTypesForPrevBillingEvent = ImmutableSet.<String>of();
        for (final BillingEvent event : subscriptionBillingEvents) {
            // Extract all in arrear /consumable usage section for that billing event.
            final List<Usage> usages = findUsageInArrearUsages(event);
            allSeenUsage.addAll(Collections2.transform(usages, new Function<Usage, UsageKey>() {
                @Override
                public UsageKey apply(final Usage input) {
                    return new UsageKey(input.getName(), event.getCatalogEffectiveDate());
                }
            }));

            // All inflight usage interval are candidates to be closed unless we see that current billing event referencing the same usage section.
            final Set<UsageKey> toBeClosed = new HashSet<UsageKey>(allSeenUsage);

            // Will contain all unit types that each BillingEvent has looked at, as defined in the catalog
            final List<ContiguousIntervalUsageInArrear> contiguousIntervalsUsageInArrear = new LinkedList<ContiguousIntervalUsageInArrear>();
            final Set<String> allSeenUnitTypesForBillingEvent = new HashSet<String>();

            for (final Usage usage : usages) {

                final UsageKey usageKey = new UsageKey(usage.getName(), event.getCatalogEffectiveDate());

                // Add inflight usage interval if non existent
                ContiguousIntervalUsageInArrear existingInterval = inFlightInArrearUsageIntervals.get(usageKey);
                if (existingInterval == null) {
                    existingInterval = usage.getUsageType() == UsageType.CAPACITY ?
                                       new ContiguousIntervalCapacityUsageInArrear(usage, accountId, invoiceId, rawSubscriptionUsage, existingTrackingIds, targetDate, rawUsageStartDate, usageDetailMode, invoiceConfig, internalTenantContext) :
                                       new ContiguousIntervalConsumableUsageInArrear(usage, accountId, invoiceId, rawSubscriptionUsage, existingTrackingIds, targetDate, rawUsageStartDate, usageDetailMode, invoiceConfig, internalTenantContext);

                    inFlightInArrearUsageIntervals.put(usageKey, existingInterval);
                }
                // Add billing event for that usage interval
                existingInterval.addBillingEvent(event);
                // Remove usage interval for toBeClosed set
                toBeClosed.remove(usageKey);

                allSeenUnitTypesForBillingEvent.addAll(existingInterval.getUnitTypes());
                contiguousIntervalsUsageInArrear.add(existingInterval);
            }

            // Add all seen unit types (across all intervals) for all intervals
            for (final ContiguousIntervalUsageInArrear contiguousIntervalUsageInArrear : contiguousIntervalsUsageInArrear) {
                contiguousIntervalUsageInArrear.addAllSeenUnitTypesForBillingEvent(event, allSeenUnitTypesForBillingEvent);
            }

            // Build the usage interval that are no longer referenced
            for (final UsageKey usageKey : toBeClosed) {
                final ContiguousIntervalUsageInArrear interval = inFlightInArrearUsageIntervals.remove(usageKey);
                if (interval != null) {
                    interval.addBillingEvent(event);
                    // No usage section for CANCEL events
                    interval.addAllSeenUnitTypesForBillingEvent(event, usages.isEmpty() ? allSeenUnitTypesForPrevBillingEvent : allSeenUnitTypesForBillingEvent);
                    usageIntervals.add(interval.build(true));
                }
            }

            allSeenUnitTypesForPrevBillingEvent = allSeenUnitTypesForBillingEvent;
        }

        for (final UsageKey usageKey : inFlightInArrearUsageIntervals.keySet()) {
            usageIntervals.add(inFlightInArrearUsageIntervals.get(usageKey).build(false));
        }
        inFlightInArrearUsageIntervals.clear();
        return usageIntervals;
    }

    private List<Usage> findUsageInArrearUsages(final BillingEvent event) throws CatalogApiException {
        if (event.getUsages().isEmpty()) {
            return Collections.emptyList();
        }

        final List<Usage> result = Lists.newArrayList();
        for (final Usage usage : event.getUsages()) {
            if (usage.getBillingMode() != BillingMode.IN_ARREAR) {
                continue;
            }
            result.add(usage);
        }
        return result;
    }

    public class SubscriptionUsageInArrearItemsAndNextNotificationDate {

        private final List<InvoiceItem> invoiceItems;
        private final Map<String, LocalDate> perUsageNotificationDates;
        private final Set<TrackingRecordId> trackingIds;

        public SubscriptionUsageInArrearItemsAndNextNotificationDate() {
            this.invoiceItems = new LinkedList<InvoiceItem>();
            this.perUsageNotificationDates = new HashMap<String, LocalDate>();
            this.trackingIds = new HashSet<>();
        }

        public void addUsageInArrearItemsAndNextNotificationDate(final String usageName, final UsageInArrearItemsAndNextNotificationDate input) {
            if (!input.getInvoiceItems().isEmpty()) {
                invoiceItems.addAll(input.getInvoiceItems());
            }

            if (input.getNextNotificationDate() != null) {
                perUsageNotificationDates.put(usageName, input.getNextNotificationDate());
            }
        }

        public void addTrackingIds(final Set<TrackingRecordId> input) {
            trackingIds.addAll(input);
        }

        public List<InvoiceItem> getInvoiceItems() {
            return invoiceItems;
        }

        public Map<String, LocalDate> getPerUsageNotificationDates() {
            return perUsageNotificationDates;
        }

        public Set<TrackingRecordId> getTrackingIds() {
            return trackingIds;
        }
    }

    private static class UsageKey {

        private final String usageName;
        private final DateTime catalogVersion;

        public UsageKey(final String usageName, final DateTime catalogVersion) {
            this.usageName = usageName;
            this.catalogVersion = catalogVersion;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            final UsageKey usageKey = (UsageKey) o;

            if (usageName != null ? !usageName.equals(usageKey.usageName) : usageKey.usageName != null) {
                return false;
            }
            return catalogVersion != null ? catalogVersion.compareTo(usageKey.catalogVersion) == 0 : usageKey.catalogVersion == null;
        }

        @Override
        public int hashCode() {
            int result = usageName != null ? usageName.hashCode() : 0;
            result = 31 * result + (catalogVersion != null ? catalogVersion.hashCode() : 0);
            return result;
        }
    }

}
