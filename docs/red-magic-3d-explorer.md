# Red Magic Tablet 3D Explorer Edition — 기기 노트

Lume Pad 2 CNSDK 지식([lume-pad-2-cnsdk-notes.md](lume-pad-2-cnsdk-notes.md))을 기준으로 이 기기에서 확인한 차이점만 적는다. 기본 원리(초기화 4가지 함정, API, 증상별 원인)는 그 문서를 먼저 볼 것.

## 기기 정보

| 항목 | 값 |
|---|---|
| 모델 | `NP02J` (product `CN_NP02J_RM`) |
| 코드네임 | `K68` — Leia 미디어 SDK가 `LPD-20W`(Lume Pad 2)와 나란히 인식하는 모델명 |
| 제조 | ZTE(nubia/RedMagic 브랜드) |
| SoC | Snapdragon 8 Gen 2 (SM8550) |
| 패널 해상도 | 2560×1600 — Lume Pad 2와 동일 |

## CNSDK 버전이 다르다

| 컴포넌트 | Red Magic | Lume Pad 2 |
|---|---|---|
| `com.leialoft.display.config` | 0.10.40 | 0.8.20 |
| `com.leia.headtrackingservice` | 0.10.18 | 0.8.20 |
| `com.leiainc.media.service` | 0.5.50 | 0.5.28 |

**두 버전 라인은 연결부를 서로 바꿔 쓸 수 없다.** `LeiaSDK.Delegate`가 인터페이스에서 추상 클래스로 바뀌는 등 API 자체가 달라졌기 때문이다(0.8.20에서 이미 한 번 바뀌었고, 0.10.x에서 또 있다). 그래서 이 기기는 자신만의 연결부(`app/libs/redmagic/leia-cnsdk.jar`, `app/src/leiaRedmagic/jniLibs/*.so`)가 필요하고, `leiaRedmagic` 빌드 플레이버로 분리돼 있다. Java/manifest/res는 `leiaLumepad`와 완전히 같다(CNSDK Java API·`PanelDriver`·`LeiaMediaSDK` 글루 코드가 동일) — `sourceSets` 블록에서 재사용한다.

빌드에 넣은 연결부는 이 기기 실측으로 CNSDK 0.10.21로 확인됐다(코어 라이브러리 자체 버전; 시스템 앱 버전 번호와 약간 다를 수 있다).

## 세 핵심 서비스 모두 시스템 앱

Lume Pad 2와 마찬가지로 `com.leialoft.display.config`, `com.leia.headtrackingservice`, `com.leiainc.media.service`가 전부 시스템 앱으로 깔려 있다. 매니페스트의 `<queries>` 선언만으로 앱에서 바로 보인다. LeiaTube 같은 서드파티 Leia 앱은 이 기기엔 안 깔려 있었다(순정 상태로 확인됨) — 참고용으로 연결부를 뽑을 다른 앱이 없다는 뜻이라, 이 기기용 연결부는 기기 자체의 시스템 앱에서 뽑아야 한다.

## 2D→3D 변환기 (`com.leiainc.media.service`)

클래스 경로가 Lume Pad 2와 완전히 동일하다 — `com.leiainc.androidsdk.video.mono.MonoVideoSurfaceRendererImpl`, `...video.stereo.StereoVideoSurfaceRendererImpl` 등. `LeiaMediaSDK`/`PanelDriver`의 리플렉션 연결 코드를 기기별로 나눌 필요가 없다.

모델 파일이 Lume Pad보다 훨씬 많다(`.dlc` 여러 개, 그중 하나는 300MB대) — 정지 이미지(3D 사진) 경로까지 포함하는 것으로 보인다. 동영상 변환 자체는 Lume Pad와 같은 방식으로 동작한다.

## ZTE 순정 3D 앱 (참고용, 둘 다 시스템 앱)

| 패키지 | 역할 |
|---|---|
| `com.zte.convert3d` | 빠른 설정 타일에서 2D→3D 변환 켜고 끄기. ProMa King의 "3DV" 서비스와 같은 계열의 ZTE 공통 인프라 |
| `com.zte.videoplayer` | ZTE 자체 3D 대응 비디오 플레이어 |

## 검증 상태

`leiaLumepad`·`leiaRedmagic` 두 플레이버 모두 실기에서 빌드·설치·확인됨(위빙, 얼굴추적, 2D→3D 변환, 밝기 복원). 커밋 `69e17da1`·`ae54b8db`.
