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

import java.util.Objects;

/**
 * Model compression options for container image builds
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
public class BuildCompression {

    static public final BuildCompression gzip = new BuildCompression().withMode(Mode.gzip);
    static public final BuildCompression estargz = new BuildCompression().withMode(Mode.estargz);
    static public final BuildCompression zstd = new BuildCompression().withMode(Mode.zstd);

    public enum Mode {
        gzip,       // gzip compression
        estargz,    // estargz compression
        zstd,       // zstd compression
        ;
    }

    private Mode mode;
    private Integer level;
    private Boolean force;

    public BuildCompression withMode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public BuildCompression withLevel(Integer level) {
        this.level = level;
        return this;
    }

    public BuildCompression withForce(Boolean force) {
        this.force = force;
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Boolean getForce() {
        return force;
    }

    public void setForce(Boolean force) {
        this.force = force;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BuildCompression that = (BuildCompression) o;
        return mode == that.mode && Objects.equals(level, that.level) && Objects.equals(force, that.force);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, level, force);
    }

    @Override
    public String toString() {
        return "BuildCompression{" +
                "mode=" + mode +
                ", level=" + level +
                ", force=" + force +
                '}';
    }
}
