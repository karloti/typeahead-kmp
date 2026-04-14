# Stage 1: Build the application
FROM gradle:9.4.1-jdk26 AS build

WORKDIR /home/gradle/src

# Copy project files
COPY --chown=gradle:gradle . .

# Fix line endings for gradlew and make executable
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Build the JS application
RUN ./gradlew :typeahead-demo:wasmJsBrowserDistribution --no-daemon

# Stage 2: Serve with Nginx
FROM nginx:alpine

# Copy the build artifacts from the build stage
# The path matches your build.gradle.kts configuration
COPY --from=build /home/gradle/src/typeahead-demo/build/dist/js/ /usr/share/nginx/html

# Configure Nginx to listen on port 8080 (required for Cloud Run)
RUN sed -i 's/listen       80;/listen       8080;/' /etc/nginx/conf.d/default.conf

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
