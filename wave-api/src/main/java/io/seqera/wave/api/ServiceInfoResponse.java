/*
 *  Copyright (c) 2023, Seqera Labs.
 *
 *  This Source Code Form is subject to the terms of the Mozilla Public
 *  License, v. 2.0. If a copy of the MPL was not distributed with this
 *  file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 *  This Source Code Form is "Incompatible With Secondary Licenses", as
 *  defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.wave.api;

import java.util.Objects;

/**
 * Implements Service info controller response object
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class ServiceInfoResponse {
    public ServiceInfo serviceInfo;

    public ServiceInfoResponse() {}

    public ServiceInfoResponse(ServiceInfo info) {
        this.serviceInfo = info;
    }

    @Override
    public String toString() {
        return "ServiceInfoResponse{" +
                "serviceInfo=" + serviceInfo +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ServiceInfoResponse that = (ServiceInfoResponse) o;
        return Objects.equals(serviceInfo, that.serviceInfo);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serviceInfo);
    }
}
