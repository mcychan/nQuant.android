# nQuant.android

A port of fast pairwise nearest neighbor based algorithm to be usable on Android. Most features should be implemented by now.

The main code of this project is licensed under the Apache 2.0 License, found at http://www.apache.org/licenses/LICENSE-2.0.html Any code released under a different licenses will be stated in the header.

# Usage
Add the following to dependency to build.gradle:

            dependencies {
              implementation project(':nQuant.master')
            }

An example app is located in the app directory and includes examples of common tasks.
If you are using java, you would call nQuant as follows:

            try {
                PnnQuantizer pnnQuantizer = new PnnLABQuantizer(filePath);
                return pnnQuantizer.convert(256, true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            
However, android does not support directly displaying bitmap in high color or indexed color format.

The demo android project is written in Java, using a button click to convert the sample image to 256 colors.<br/><br/>
[![demo app screenshot][1]][1]

  [1]: https://i.stack.imgur.com/N1tQi.png
