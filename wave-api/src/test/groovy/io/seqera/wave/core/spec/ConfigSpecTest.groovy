/*
 * Copyright 2024, Seqera Labs
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

package io.seqera.wave.core.spec

import spock.lang.Specification

import java.time.Instant

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class ConfigSpecTest extends Specification {

    def 'should parse manifest spec' ( ){
        given:
        def SPEC = '''
            {
              "architecture":"amd64",
              "config":{
                "Hostname":"the-host-name",
                "Domainname":"the-domain-name",
                "User":"foo",
                "AttachStdin":false,
                "AttachStdout":false,
                "AttachStderr":false,
                "Tty":false,
                "OpenStdin":false,
                "StdinOnce":false,
                "Env":[
                  "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                ],
                "Cmd":[
                  "/bin/sh"
                ],
                "Image":"",
                "Volumes":null,
                "WorkingDir":"/some/path",
                "Entrypoint": ["/some","entry.sh"],
                "OnBuild":null,
                "Labels":{
            
                }
              },
              "container":"4be9f6b4406ec142e457fd7c43ff338511ab338b33585c30805ba2d5d3da29e3",
              "created":"2020-01-24T15:39:30.564518517Z",
              "docker_version":"17.09.0-ce",
              "id":"f235879f79194a5e3d4b10c3b714c36669e8e98160ba73bd9b044fdec624ceaf",
              "os":"linux",
              "rootfs":{
                "type":"layers",
                "diff_ids":[
                  "sha256:e07ee1baac5fae6a26f30cabfe54a36d3402f96afda318fe0a96cec4ca393359"
                ]
              }
            }
            '''
        when:
        def manifest = ConfigSpec.parse(SPEC)
        then:
        manifest.architecture == 'amd64'
        manifest.container == '4be9f6b4406ec142e457fd7c43ff338511ab338b33585c30805ba2d5d3da29e3'
        manifest.created == Instant.parse('2020-01-24T15:39:30.564518517Z')
        and:
        manifest.config.hostName == 'the-host-name'
        manifest.config.domainName == 'the-domain-name'
        manifest.config.user == 'foo'
        manifest.config.env == ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin']
        manifest.config.cmd == ['/bin/sh']
        manifest.config.workingDir == '/some/path'
        manifest.config.entrypoint == ["/some","entry.sh"]
        and:
        manifest.rootfs.type == 'layers'
        manifest.rootfs.diff_ids == ['sha256:e07ee1baac5fae6a26f30cabfe54a36d3402f96afda318fe0a96cec4ca393359']
    }

    def 'should parse spec v1' () {
        given:
        def SPEC = '''
            {
                "schemaVersion": 1,
                "name": "biocontainers/fastqc",
                "tag": "0.11.9--0",
                "architecture": "amd64",
                "fsLayers": [
                    {
                        "blobSum": "sha256:6d92b3a49ebfad5fe895550c2cb24b6370d61783aa4f979702a94892cbd19077"
                    },
                    {
                        "blobSum": "sha256:a3ed95caeb02ffe68cdd9fd84406680ae93d633cb16422d00e8a7c22955b46d4"
                    },
                    {
                        "blobSum": "sha256:10c3bb32200bdb5006b484c59b5f0c71b4dbab611d33fca816cd44f9f5ce9e3c"
                    }
                ],
                "history": [
                    {
                        "v1Compatibility": "{\\"architecture\\":\\"amd64\\",\\"config\\":{\\"Hostname\\":\\"foo-host\\",\\"Domainname\\":\\"foo-domain\\",\\"User\\":\\"foo-user\\",\\"AttachStdin\\":false,\\"AttachStdout\\":false,\\"AttachStderr\\":false,\\"Tty\\":false,\\"OpenStdin\\":false,\\"StdinOnce\\":false,\\"Env\\":[\\"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\\"],\\"Cmd\\":[\\"/bin/bash\\"],\\"Image\\":\\"\\",\\"Volumes\\":null,\\"WorkingDir\\":\\"/some/work/dir\\",\\"Entrypoint\\":[\\"entry.sh\\"],\\"OnBuild\\":null,\\"Labels\\":{}},\\"container\\":\\"4be9f6b4406ec142e457fd7c43ff338511ab338b33585c30805ba2d5d3da29e3\\",\\"container_config\\":{\\"Hostname\\":\\"4be9f6b4406e\\",\\"Domainname\\":\\"\\",\\"User\\":\\"\\",\\"AttachStdin\\":false,\\"AttachStdout\\":false,\\"AttachStderr\\":false,\\"Tty\\":false,\\"OpenStdin\\":false,\\"StdinOnce\\":false,\\"Env\\":[\\"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\\"],\\"Cmd\\":[\\"/bin/sh\\"],\\"Image\\":\\"bgruening/busybox-bash:0.1\\",\\"Volumes\\":null,\\"WorkingDir\\":\\"\\",\\"Entrypoint\\":null,\\"OnBuild\\":null,\\"Labels\\":{}},\\"created\\":\\"2020-01-24T15:39:30.564518517Z\\",\\"docker_version\\":\\"17.09.0-ce\\",\\"id\\":\\"f235879f79194a5e3d4b10c3b714c36669e8e98160ba73bd9b044fdec624ceaf\\",\\"os\\":\\"linux\\",\\"parent\\":\\"b7c0567175be2a551a8ed4e60d695d33347ebae5d8cfc4a5d0381e0ce3c34222\\"}"
                    },
                    {
                        "v1Compatibility": "{\\"id\\":\\"b7c0567175be2a551a8ed4e60d695d33347ebae5d8cfc4a5d0381e0ce3c34222\\",\\"parent\\":\\"32dbc9f4b6f9f15dfcce38773db21d7aadfc059242a821fb98bc8cf0997d05ce\\",\\"created\\":\\"2016-05-09T06:21:02.266124295Z\\",\\"container_config\\":{\\"Cmd\\":[\\"/bin/sh -c #(nop) CMD [\\\\\\"/bin/sh\\\\\\" \\\\\\"-c\\\\\\" \\\\\\"/bin/bash\\\\\\"]\\"]},\\"author\\":\\"Bjoern Gruening \\\\u003cbjoern.gruening@gmail.com\\\\u003e\\",\\"throwaway\\":true}"
                    },
                    {
                        "v1Compatibility": "{\\"id\\":\\"32dbc9f4b6f9f15dfcce38773db21d7aadfc059242a821fb98bc8cf0997d05ce\\",\\"parent\\":\\"ce56e62b24426c8b57a4e3f23fcf0cab885f0d4c7669f2b3da6d17b4b9ac7268\\",\\"created\\":\\"2016-05-09T06:21:02.050926818Z\\",\\"container_config\\":{\\"Cmd\\":[\\"/bin/sh -c #(nop) ADD file:8583f81843640f66efa0cce8dc4f49fd769ed485caa0678ef455aa65876b03e2 in /bin/bash\\"]}}"
                    }
                ]
            }
            '''

        when:
        def manifest = ConfigSpec.parseV1(SPEC)
        then:
        manifest.architecture == 'amd64'
        manifest.container == '4be9f6b4406ec142e457fd7c43ff338511ab338b33585c30805ba2d5d3da29e3'
        manifest.created == Instant.parse('2020-01-24T15:39:30.564518517Z')
        and:
        manifest.config.user == 'foo-user'
        manifest.config.domainName == 'foo-domain'
        manifest.config.hostName == 'foo-host'
        and:
        manifest.config.env == ['PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin']
        manifest.config.cmd == ['/bin/bash']
        and:
        manifest.config.entrypoint == ['entry.sh']
        manifest.config.workingDir == '/some/work/dir'
        and:
        manifest.rootfs.diff_ids == []

    }

    def 'should create a config spec' () {
        when:
        def config1 = new ConfigSpec.Config()
        then:
        config1.hostName == null
        config1.domainName == null
        config1.workingDir == null
        config1.user == null
        and:
        !config1.attachStdin
        !config1.attachStdout
        !config1.attachStderr
        and:
        config1.env == []
        config1.cmd == []
        config1.entrypoint == []

        when:
        def config2 = new ConfigSpec.Config(
                Hostname: 'foo',
                Domainname: 'bar',
                User: 'Me',
                AttachStdin: true,
                AttachStdout: true,
                AttachStderr: true,
                Cmd: ['this','that'],
                Env: ['Foo=1', 'Bar=2'],
                Entrypoint: ['some','entry']
        )
        then:
        config2.hostName == 'foo'
        config2.domainName == 'bar'
        config2.user == 'Me'
        and:
        config2.attachStdin
        config2.attachStdout
        config2.attachStderr
        and:
        config2.env == ['Foo=1', 'Bar=2']
        config2.cmd == ['this','that']
        config2.entrypoint == ['some','entry']
    }

}
