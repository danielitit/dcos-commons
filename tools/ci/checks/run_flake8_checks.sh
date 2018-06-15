#!/bin/bash


DOCKER_TAG=${DOCKER_TAG:-latest}
DOCKER_IMAGE=${DOCKER_IMAGE:-mesosphere/dcos-commons:${DOCKER_TAG}}

docker run --rm -ti \
    -v $(pwd):/build:ro \
    -w /build \
        mesosphere/dcos-commons:elezar-dev \
            flake8 $*
