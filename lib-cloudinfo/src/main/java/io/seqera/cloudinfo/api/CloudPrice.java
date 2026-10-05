/*
 * Copyright 2026, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.seqera.cloudinfo.api;

import java.util.Objects;

/**
 * Model Cloudinfo product price
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CloudPrice {

    private Float price;
    private String zone;

    /**
     * The provider's account-independent zone identifier, e.g. the AWS AZ ID {@code euw1-az1}.
     * AWS maps AZ names to physical zones per account, so {@link #zone} names a zone in
     * CloudInfo's own account; join on this field to match a customer's zones. {@code null} when
     * the provider has none or the backend predates cloudinfo 0.26.0.
     */
    private String zoneId;

    public CloudPrice() {
    }

    public CloudPrice(Float price, String zone) {
        this.price = price;
        this.zone = zone;
    }

    public Float getPrice() {
        return price;
    }

    public void setPrice(Float price) {
        this.price = price;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getZoneId() {
        return zoneId;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CloudPrice that = (CloudPrice) o;
        return Objects.equals(price, that.price) && Objects.equals(zone, that.zone) && Objects.equals(zoneId, that.zoneId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(price, zone, zoneId);
    }

    @Override
    public String toString() {
        return "CloudPrice[price=" + price + ", zone=" + zone + ", zoneId=" + zoneId + "]";
    }
}
