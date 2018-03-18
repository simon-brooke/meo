#!/usr/bin/env bash

yarn install

mkdir bin
rm -rf ./dist
lein dist
cp target/meo.jar bin/


## Icons setup
iconsdir="`dirname \"$0\"`/build/icons"

if [ ! -e ${iconsdir} ]
then
  mkdir "${iconsdir}"
else
  if [ ! -d "${iconsdir}" ]
  then
    echo "${iconsdir} exists but is not a directory: exiting" >&2
    exit 1
  else
    if [ ! -w "${iconsdir}" ]
    then
      echo "${iconsdir} exists but is not writable: exiting" >&2
      exit 2
    fi
  fi
fi

## Check for ImageMagick convert
which convert >/dev/null
if [ "$?" = "0" ]
then
  cp rn-app/images/meo.png ${iconsdir}

  pushd ${iconsdir} >/dev/null

  convert meo.png -resize 128x128 icon.png
  for size in 16 32 64 128 256
  do
    convert meo.png -resize ${size}x${size} -background transparent meo-${size}.png
  done

  convert meo-16.png \
    meo-32.png \
    meo-64.png \
    meo-128.png \
    meo-256.png -colors 256 meo.ico

  popd >/dev/null
else
  echo "`convert` is not installed: not creating `meo.ico` file." >&2
fi

which png2icns >/dev/null
if [ "$?" = "0" ]
then
  pushd ${iconsdir} >/dev/null
  png2icns meo.icns meo.png >/dev/null
  popd >/dev/null
else
  echo "`png2icns` is not installed: not creating `meo.icns` file." >&2
fi


PLATFORMS=$1
ELECTRON_BUILDER_COMPRESSION_LEVEL=3

if [ "$2" == "release" ]; then
  echo "Publishing Release"
  ./node_modules/.bin/electron-builder --publish always $1
else
  echo "Publishing Beta Version"
  ./node_modules/.bin/electron-builder -c electron-builder-beta.yml --publish always $1
fi
