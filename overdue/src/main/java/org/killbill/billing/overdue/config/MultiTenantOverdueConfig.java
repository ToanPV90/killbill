/*
 * Copyright 2020-2023 Equinix, Inc
 * Copyright 2014-2023 The Billing Project, LLC
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

package org.killbill.billing.overdue.config;

import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.killbill.billing.callcontext.InternalTenantContext;
import org.killbill.billing.util.config.definition.KillbillConfig;
import org.killbill.billing.util.config.definition.OverdueConfig;
import org.killbill.billing.util.config.tenant.CacheConfig;
import org.killbill.billing.util.config.tenant.MultiTenantConfigBase;
import org.killbill.billing.util.glue.KillBillModule;
import org.skife.config.TimeSpan;

public class MultiTenantOverdueConfig extends MultiTenantConfigBase implements OverdueConfig {

    private final OverdueConfig staticConfig;

    @Inject
    public MultiTenantOverdueConfig(@Named(KillBillModule.STATIC_CONFIG) final OverdueConfig staticConfig, final CacheConfig cacheConfig) {
        super(cacheConfig);
        this.staticConfig = staticConfig;
    }

    @Override
    public List<TimeSpan> getRescheduleIntervalOnLock() {
        return staticConfig.getRescheduleIntervalOnLock();
    }

    @Override
    public List<TimeSpan> getRescheduleIntervalOnLock(final InternalTenantContext tenantContext) {
        final String result = getStringTenantConfig("getRescheduleIntervalOnLock", tenantContext);
        if (result != null) {
            return convertToListTimeSpan(result, "getRescheduleIntervalOnLock");
        }
        return getRescheduleIntervalOnLock();

    }

    @Override
    protected Class<? extends KillbillConfig> getConfigClass() {
        return OverdueConfig.class;
    }
}
