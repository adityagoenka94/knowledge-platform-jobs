FROM anandp504/flink:1.10.1-scala_2.12
COPY target/jobs-distribution-1.0.tar.gz /tmp
USER flink
RUN tar -xvf /tmp/jobs-distribution-1.0.tar.gz -C $FLINK_HOME/lib/
USER root
RUN rm -f /tmp/jobs-distribution-1.0.tar.gz
USER flink
