FROM eclipse-temurin:25-jre-noble

COPY out/consultant/assembly.dest/out.jar /opt/consultant/consultant.jar

# Optimization for low memory footprint.
ENV JAVA_TOOL_OPTIONS="-Xint"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/consultant/consultant.jar"]