/*
 * Copyright (c) 2019-2020, Seqera Labs.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * This Source Code Form is "Incompatible With Secondary Licenses", as
 * defined by the Mozilla Public License, v. 2.0.
 */

package io.seqera.tower.crypto

import spock.lang.Specification

/**
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
class HmacSha1SignatureTest extends Specification {


    def 'should check signature' () {
        given:
        String data = '{"zen":"Non-blocking is better than blocking.","hook_id":217677562,"hook":{"type":"Repository","id":217677562,"name":"web","active":true,"events":["push"],"config":{"content_type":"json","insecure_ssl":"1","secret":"********","url":"http://dce710f187d0.ngrok.io/api/actions/events/github/2"},"updated_at":"2020-06-04T06:59:14Z","created_at":"2020-06-04T06:59:14Z","url":"https://api.github.com/repos/nextflow-io/hello/hooks/217677562","test_url":"https://api.github.com/repos/nextflow-io/hello/hooks/217677562/test","ping_url":"https://api.github.com/repos/nextflow-io/hello/hooks/217677562/pings","last_response":{"code":null,"status":"unused","message":null}},"repository":{"id":22641551,"node_id":"MDEwOlJlcG9zaXRvcnkyMjY0MTU1MQ==","name":"hello","full_name":"nextflow-io/hello","private":false,"owner":{"login":"nextflow-io","id":6698688,"node_id":"MDEyOk9yZ2FuaXphdGlvbjY2OTg2ODg=","avatar_url":"https://avatars3.githubusercontent.com/u/6698688?v=4","gravatar_id":"","url":"https://api.github.com/users/nextflow-io","html_url":"https://github.com/nextflow-io","followers_url":"https://api.github.com/users/nextflow-io/followers","following_url":"https://api.github.com/users/nextflow-io/following{/other_user}","gists_url":"https://api.github.com/users/nextflow-io/gists{/gist_id}","starred_url":"https://api.github.com/users/nextflow-io/starred{/owner}{/repo}","subscriptions_url":"https://api.github.com/users/nextflow-io/subscriptions","organizations_url":"https://api.github.com/users/nextflow-io/orgs","repos_url":"https://api.github.com/users/nextflow-io/repos","events_url":"https://api.github.com/users/nextflow-io/events{/privacy}","received_events_url":"https://api.github.com/users/nextflow-io/received_events","type":"Organization","site_admin":false},"html_url":"https://github.com/nextflow-io/hello","description":"Classic hello world script in Nextflow language","fork":false,"url":"https://api.github.com/repos/nextflow-io/hello","forks_url":"https://api.github.com/repos/nextflow-io/hello/forks","keys_url":"https://api.github.com/repos/nextflow-io/hello/keys{/key_id}","collaborators_url":"https://api.github.com/repos/nextflow-io/hello/collaborators{/collaborator}","teams_url":"https://api.github.com/repos/nextflow-io/hello/teams","hooks_url":"https://api.github.com/repos/nextflow-io/hello/hooks","issue_events_url":"https://api.github.com/repos/nextflow-io/hello/issues/events{/number}","events_url":"https://api.github.com/repos/nextflow-io/hello/events","assignees_url":"https://api.github.com/repos/nextflow-io/hello/assignees{/user}","branches_url":"https://api.github.com/repos/nextflow-io/hello/branches{/branch}","tags_url":"https://api.github.com/repos/nextflow-io/hello/tags","blobs_url":"https://api.github.com/repos/nextflow-io/hello/git/blobs{/sha}","git_tags_url":"https://api.github.com/repos/nextflow-io/hello/git/tags{/sha}","git_refs_url":"https://api.github.com/repos/nextflow-io/hello/git/refs{/sha}","trees_url":"https://api.github.com/repos/nextflow-io/hello/git/trees{/sha}","statuses_url":"https://api.github.com/repos/nextflow-io/hello/statuses/{sha}","languages_url":"https://api.github.com/repos/nextflow-io/hello/languages","stargazers_url":"https://api.github.com/repos/nextflow-io/hello/stargazers","contributors_url":"https://api.github.com/repos/nextflow-io/hello/contributors","subscribers_url":"https://api.github.com/repos/nextflow-io/hello/subscribers","subscription_url":"https://api.github.com/repos/nextflow-io/hello/subscription","commits_url":"https://api.github.com/repos/nextflow-io/hello/commits{/sha}","git_commits_url":"https://api.github.com/repos/nextflow-io/hello/git/commits{/sha}","comments_url":"https://api.github.com/repos/nextflow-io/hello/comments{/number}","issue_comment_url":"https://api.github.com/repos/nextflow-io/hello/issues/comments{/number}","contents_url":"https://api.github.com/repos/nextflow-io/hello/contents/{+path}","compare_url":"https://api.github.com/repos/nextflow-io/hello/compare/{base}...{head}","merges_url":"https://api.github.com/repos/nextflow-io/hello/merges","archive_url":"https://api.github.com/repos/nextflow-io/hello/{archive_format}{/ref}","downloads_url":"https://api.github.com/repos/nextflow-io/hello/downloads","issues_url":"https://api.github.com/repos/nextflow-io/hello/issues{/number}","pulls_url":"https://api.github.com/repos/nextflow-io/hello/pulls{/number}","milestones_url":"https://api.github.com/repos/nextflow-io/hello/milestones{/number}","notifications_url":"https://api.github.com/repos/nextflow-io/hello/notifications{?since,all,participating}","labels_url":"https://api.github.com/repos/nextflow-io/hello/labels{/name}","releases_url":"https://api.github.com/repos/nextflow-io/hello/releases{/id}","deployments_url":"https://api.github.com/repos/nextflow-io/hello/deployments","created_at":"2014-08-05T11:49:08Z","updated_at":"2020-02-18T03:58:10Z","pushed_at":"2020-02-18T03:58:08Z","git_url":"git://github.com/nextflow-io/hello.git","ssh_url":"git@github.com:nextflow-io/hello.git","clone_url":"https://github.com/nextflow-io/hello.git","svn_url":"https://github.com/nextflow-io/hello","homepage":"http://www.nextflow.io","size":25,"stargazers_count":0,"watchers_count":0,"language":"Nextflow","has_issues":true,"has_projects":true,"has_downloads":true,"has_wiki":true,"has_pages":false,"forks_count":17,"mirror_url":null,"archived":false,"disabled":false,"open_issues_count":0,"license":{"key":"gpl-3.0","name":"GNU General Public License v3.0","spdx_id":"GPL-3.0","url":"https://api.github.com/licenses/gpl-3.0","node_id":"MDc6TGljZW5zZTk="},"forks":17,"open_issues":0,"watchers":0,"default_branch":"master"},"sender":{"login":"pditommaso","id":816968,"node_id":"MDQ6VXNlcjgxNjk2OA==","avatar_url":"https://avatars0.githubusercontent.com/u/816968?v=4","gravatar_id":"","url":"https://api.github.com/users/pditommaso","html_url":"https://github.com/pditommaso","followers_url":"https://api.github.com/users/pditommaso/followers","following_url":"https://api.github.com/users/pditommaso/following{/other_user}","gists_url":"https://api.github.com/users/pditommaso/gists{/gist_id}","starred_url":"https://api.github.com/users/pditommaso/starred{/owner}{/repo}","subscriptions_url":"https://api.github.com/users/pditommaso/subscriptions","organizations_url":"https://api.github.com/users/pditommaso/orgs","repos_url":"https://api.github.com/users/pditommaso/repos","events_url":"https://api.github.com/users/pditommaso/events{/privacy}","received_events_url":"https://api.github.com/users/pditommaso/received_events","type":"User","site_admin":false}}'

        when:
        String hmac = HmacSha1Signature.compute(data, "5QdPGQZAlTwEF6w8Ccmwt0")
        then:
        hmac == '8d5b63403b3439d88f11978263e1ea1b04412eb6'
    }
}