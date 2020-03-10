#!/bin/bash
# Assumes that COMMIT, DOCKER_USER and DOCKER_PASS to be set
VERSION=${1:-"latest"}
COMMIT=$2
DOCKER_REGISTRY=quay.io
REPO=${DOCKER_REGISTRY}/enmasse/systemtests-external-clients

if [ -n "${TRAVIS_TAG}" ]
then
    VERSION="${TRAVIS_TAG}"
fi

mvn clean install
docker build --build-arg version=${VERSION} -t ${REPO}:${COMMIT} . || exit 1

if [ "$TRAVIS_PULL_REQUEST" == "false" ]; then
    echo "Pushing $REPO:$COMMIT"
    docker login -u $DOCKER_USER -p $DOCKER_PASS $DOCKER_REGISTRY || exit 1
    docker push $REPO:$COMMIT || exit 1
    if [ "$TRAVIS_BRANCH" == "master" ]; then
        echo "Pushing $REPO:$VERSION"
        docker tag $REPO:$COMMIT $REPO:$VERSION || exit 1
        docker push $REPO:$VERSION || exit 1
    fi
fi
