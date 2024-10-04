/*
 *  Wave, containers provisioning service
 *  Copyright (c) 2023-2024, Seqera Labs
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.seqera.wave.api;

/**
 * Define the possible container security scan modes
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public enum ScanMode {
    none,       // no scan is performed
    async,      // scan is carried out asynchronously once the build is complete
    required,   // scan completion is required for the container request to reach 'DONE' status
    ;

    public boolean asBoolean() {
        return this != none;
    }
}
