#!/bin/bash

#
# Copyright 2026, Seqera Labs
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#

# Publishes a library to Maven repository (S3).
# Usage:
#   ./publish.sh <module-name>               # Release publish (checks if exists)
#   SNAPSHOT=true ./publish.sh <module-name> # Snapshot publish (appends -SNAPSHOT)

set -e

if [ $# -ne 1 ]; then
  echo "Usage: $0 <module-name>"
  echo "Example: $0 lib-crypto"
  echo "Example: SNAPSHOT=true $0 lib-crypto"
  exit 1
fi

if [[ ! -n "$AWS_REGION" && ! -n "$AWS_DEFAULT_REGION" ]]; then
  export AWS_DEFAULT_REGION="eu-west-1"
fi

NAME=$1
BASE_VERSION=$(cat $NAME/VERSION)
PUBLISH_REPO_URL=${PUBLISH_REPO_URL:-s3://maven.seqera.io/snapshots}

if [ "$SNAPSHOT" = "true" ]; then
  # Snapshot publishing - can be overwritten, no existence check needed
  VERSION="${BASE_VERSION}-SNAPSHOT"
  echo "Publishing snapshot '$NAME-$VERSION' to $PUBLISH_REPO_URL"
  ./gradlew $NAME:publishMavenPublicationToSeqeraRepository -Psnapshot=true
else
  # Release publishing - check if already exists
  VERSION="$BASE_VERSION"
  BASE_URL=${PUBLISH_REPO_URL#s3://}
  BUCKET=$(dirname $BASE_URL)
  BASE_PATH=$(basename $BASE_URL)
  KEY=$BASE_PATH/io/seqera/$NAME/$VERSION/$NAME-$VERSION.pom

  echo "Publishing release '$NAME-$VERSION' to $BUCKET/$KEY"
  ret=$(aws s3api head-object --bucket $BUCKET --key $KEY 2>&1) && {
    # already exists => just a message
    echo "NOTE: Library $NAME-$VERSION already exist - skipping publishing"
  } || {
    if [[ $ret == *"Not Found"* ]]; then
      # the lib does not exist => publish it
      ./gradlew $NAME:publishMavenPublicationToSeqeraRepository
    else
      # print the error message
      echo $ret >&2
      exit 1
    fi
  }
fi
