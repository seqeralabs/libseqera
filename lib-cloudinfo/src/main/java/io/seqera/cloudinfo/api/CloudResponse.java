/*
 * Copyright 2025, Seqera Labs
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

import java.util.List;
import java.util.Objects;

/**
 * Model Cloudinfo response object
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class CloudResponse {

    private List<CloudProduct> products;

    public CloudResponse() {
    }

    public CloudResponse(List<CloudProduct> products) {
        this.products = products;
    }

    public List<CloudProduct> getProducts() {
        return products;
    }

    public void setProducts(List<CloudProduct> products) {
        this.products = products;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CloudResponse that = (CloudResponse) o;
        return Objects.equals(products, that.products);
    }

    @Override
    public int hashCode() {
        return Objects.hash(products);
    }

    @Override
    public String toString() {
        return "CloudResponse[products=" + products + "]";
    }
}
