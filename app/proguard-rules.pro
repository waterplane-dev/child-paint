# R8 rules for the release build.
#
# The app uses no reflection, no serialisation library, and no dynamic class
# loading: view binding is generated at compile time and every class is reached
# from MainActivity or the manifest. The defaults in
# proguard-android-optimize.txt are therefore enough, and this file is
# deliberately empty rather than padded with defensive -keep rules that would
# undo the shrinking.
#
# Line numbers are kept so a crash report from a release build is readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
