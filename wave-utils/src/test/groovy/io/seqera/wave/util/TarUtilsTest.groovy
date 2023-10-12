/*
 * Copyright 2023, Seqera Labs
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

package io.seqera.wave.util

import spock.lang.Specification

import java.nio.file.Files

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class TarUtilsTest extends Specification {

    def 'should uncompress a tar file to the target dir' () {
        given:
        def folder = Files.createTempDirectory('test')
        def source = folder.resolve('source')
        def target = folder.resolve('target')
        Files.createDirectory(source)
        Files.createDirectory(target)
        and:
        source.resolve('foo.txt').text  = 'Foo'
        source.resolve('bar.txt').text  = 'Bar'
        FileUtils.setPermissions(source.resolve('bar.txt'), 'rwx------')
        and:
        source.resolve('subdir')
        Files.createDirectory(source.resolve('subdir'))
        source.resolve('subdir/baz.txt').text = 'Baz'
        and:
        FileUtils.setLastModified(source.resolve('foo.txt'), 1_691_100_000)
        FileUtils.setLastModified(source.resolve('bar.txt'), 1_691_200_000)
        FileUtils.setLastModified(source.resolve('subdir/baz.txt'), 1_691_300_000)

        when:
        def layer = new Packer().layer(source)
        and:
        def gzip = layer.location.replace('data:','').decodeBase64()
        and:
        TarUtils.untarGzip( new ByteArrayInputStream(gzip), target)
        then:
        target.resolve('foo.txt').text == 'Foo'
        target.resolve('bar.txt').text == 'Bar'
        target.resolve('subdir/baz.txt').text == 'Baz'
        and:
        FileUtils.getPermissions(target.resolve('bar.txt')) == 'rwx------'
        and:
        Files.getLastModifiedTime(target.resolve('foo.txt')).toMillis() == 1_691_100_000
        Files.getLastModifiedTime(target.resolve('bar.txt')).toMillis() == 1_691_200_000
        Files.getLastModifiedTime(target.resolve('subdir/baz.txt')).toMillis() ==  1_691_300_000

        cleanup:
        folder?.deleteDir()
    }

}
