FROM debian:latest

RUN apt update
RUN apt install -y gcc g++ openjdk-17-jdk
COPY . /workspace
WORKDIR /workspace
RUN ./gradlew --quiet --console=plain clean vecAarch64SharedLibrary

CMD cat build/libs/vec/shared/libvec.so
