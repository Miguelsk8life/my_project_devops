#!/bin/bash


while getopts "t:" opt; do
  case ${opt} in
    t ) IMAGE_TAG=$OPTARG ;;
    \? ) echo "Usage: $0 -t <tag_version>"; exit 1 ;;
  esac
done

if [ -z "$IMAGE_TAG" ]; then
    echo "Error: The -t parameter is mandatory to define the image tag."
    exit 1
fi

echo "--- Building Docker image with tag: ${IMAGE_TAG} ---"
export IMAGE_TAG
docker compose build web-app
