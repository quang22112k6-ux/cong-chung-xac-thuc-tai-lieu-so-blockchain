#!/usr/bin/env sh
# Biên dịch và chạy bộ kiểm thử.
set -e
cd "$(dirname "$0")"
JAVAC="javac"
command -v javac >/dev/null 2>&1 || JAVAC="java -m jdk.compiler/com.sun.tools.javac.Main"
rm -rf out && mkdir -p out
$JAVAC -encoding UTF-8 -d out $(find src/main/java src/test/java -name '*.java')
cp -r src/main/resources/* out/
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp out vn.edu.notary.SelfTest
