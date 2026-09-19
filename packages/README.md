# AGC Distribution Packages

이 디렉토리는 AGC의 배포용 빌드 바이너리(JAR) 패키지를 보관하는 위치입니다.

## 포함된 패키지 아티팩트

1. **`agc-paperclip-26.2.local-SNAPSHOT.jar`** (약 58.7 MB)
   - **실행 가능한 완전한 배포본 (All-in-One Runnable JAR)**
   - Paperclip 번들러가 적용되어 있어 Mojang 바닐라 서버 데이터 및 모든 종속성 라이브러리가 자동 번들링됩니다.
   - 실행 명령:
     ```bash
     java -Xms4G -Xmx8G -jar packages/agc-paperclip-26.2.local-SNAPSHOT.jar --nogui
     ```

2. **`agc-server-26.2.local-SNAPSHOT.jar`** (약 30.1 MB)
   - Mojang 네임스페이스 맵핑 기반의 AGC 서버 코어 런타임 JAR입니다.

## GitHub Release 패키지 다운로드

최신 릴리스 바이너리는 GitHub Releases 웹 페이지에서도 직접 다운로드할 수 있습니다:
- **GitHub Release URL**: [https://github.com/gdlls/AGC/releases/tag/v26.2.0](https://github.com/gdlls/AGC/releases/tag/v26.2.0)
