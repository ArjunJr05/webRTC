# webRTC

Android WebRTC chat application with a Spring Boot WebSocket signaling backend.

## Repository Contents

- `app/`: Android client written in Kotlin.
- `webrtc-httpp/`: Maven-based Java signaling server.
- `docs/`: GitHub Pages site and generated Java API documentation.

## Backend Docs

- GitHub Pages landing page: `https://arjunjr05.github.io/webRTC/`
- Java API docs entry: `https://arjunjr05.github.io/webRTC/apidocs/`

## Local Commands

### Android

```powershell
./gradlew :app:assembleDebug
```

### Backend

```powershell
cd webrtc-httpp
mvn package -DskipTests
java -jar target/demo-0.0.1-SNAPSHOT.jar
```

### Regenerate JavaDoc

```powershell
cd webrtc-httpp
mvn javadoc:javadoc
```

After regenerating JavaDoc, copy the contents of `webrtc-httpp/target/site/apidocs/` into `docs/apidocs/` before pushing.