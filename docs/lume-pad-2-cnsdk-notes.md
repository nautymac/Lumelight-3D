# Lume Pad 2 (Leia CNSDK) 개발 노트

Artemis 포크에 나안 3D를 붙이면서 실기로 확인한 것들. 3D 비디오 플레이어를 새로 만들 때 같은 벽에 다시 부딪히지 않도록 정리했다.

기준일 2026-09-04. 기기는 Lume Pad 2 (`LumePadGen2` / `LPD_20W`), Android 13.

---

## 1. 패널이 어떤 물건인가

렌티큘러가 아니다. **회절 백라이트(DLB)로 8개 방향에 빛을 쏘는 라이트필드 패널**이고, 얼굴 위치를 90fps로 추적해 어느 방향이 어느 눈인지 실시간으로 다시 계산한다.

기기가 보고한 값:

```
Number of Views  : 8 x 1
Panel Resolution : 2560 x 1600
View Resolution  : 1920 x 1200      ← 눈당 유효 해상도
backlight_type   : GRATING_4x4
Display Size     : 266 x 167 mm
Dot Pitch        : 0.10389 x 0.104375 mm
System Disparity : 4 px
Convergence Distance : 521.53
Camera Center    : [-7.5, 91.5, 0]
n : 1.6   Theta : -0.51941   s : 10.8025   d/n : 0.6926
p/du : 3   p/dv : 1
```

**공간 분할이 아니라 각도 분할이다.** 렌티큘러처럼 가로를 8등분했다면 뷰당 320px(2560÷8)이어야 하는데 실제 View Resolution은 1920×1200 — 패널의 3/4다. 8개 뷰가 화면을 나눠 갖는 게 아니라 8개의 각도 영역이 각각 거의 전체 해상도를 본다.

`n`(굴절률), `Theta`(각도), `d/n`(광학 거리)가 광학 파라미터라는 점, `p/du : 3`이 격자 주기가 RGB 서브픽셀 3개에 대응한다는 점이 이를 뒷받침한다. 다만 8뷰를 시분할로 만드는지 회절 차수로 동시에 만드는지 같은 세부 구현은 Leia 비공개 영역이라 확인 못 했다.

**따라서 셰이더로 정적 컬럼 위빙을 흉내내는 접근은 원리적으로 불가능하다.** 고정 패턴으로는 움직이는 머리를 따라갈 수 없다. 위빙은 반드시 CNSDK에 맡겨야 한다.

> **주의: 물리적 기본 방향이 세로다.** `wm size`가 `Physical size: 1600x2560`을 돌려준다. 회전에 따라 달라지는 값(`getRealMetrics` 등)으로 종횡비를 계산하면 세로로 시작할 때 0.625가 나온다. 긴 변·짧은 변을 정렬해서 쓸 것.

---

## 2. 역할 분담

```
앱          : 좌우 눈 두 장(SBS) 생성까지
CNSDK       : 8개 각도 영역에 배분 + 얼굴 추적으로 조향
```

앱은 SBS만 만들면 되고, 그것을 8방향으로 어떻게 뿌릴지는 전적으로 CNSDK가 정한다. 비디오 플레이어라면:

- **SBS 3D 영상 파일** → 디코더 출력을 CNSDK 서피스에 바로 넣고 `numTiles(2,1)`
- **일반 2D 영상** → 깊이 추정 + DIBR로 SBS를 만든 뒤 같은 경로

정식 Moonlight3D(`com.moonlight.leia`)가 하는 일이 전자다. 후자는 SDK에 API만 있고 구현이 비어 있어(10절) 직접 만들어야 한다.

---

## 3. CNSDK 구하기

**공개 배포처가 없다.** Maven Central에도, JitPack에도, Leia GitHub 조직에도 Android CNSDK 아티팩트가 없다. `developers.leiainc.com`은 접속되지 않는다.

정공법은 `developers@leiainc.com`에 라이선스 신청. 개인 실험 목적이라면 기기에 설치된 Leia 앱(예: `com.moonlight.leia`)의 APK에서 추출할 수 있지만, **그렇게 얻은 빌드는 배포 불가**다. 저장소에 커밋하지 말 것.

필요한 것:

```
app/libs/leia-cnsdk.jar                        (dex2jar 로 복원한 클래스)
app/src/<flavor>/jniLibs/arm64-v8a/*.so        (libleiaSDK.so 등)
app/src/<flavor>/jniLibs/armeabi-v7a/*.so
```

R8이 복원 바이트코드에 대해 경고를 낸다:

```
Expected stack map table for method with non-linear control flow.
In later version of R8, the method may be assumed not reachable.
```

현재는 `-keep` 규칙이 지켜주지만 AGP를 올릴 때 깨질 수 있는 지점이다.

### 3-1. 0.8.20으로 교체 (2026-09-13)

처음엔 정식 Moonlight3D에 든 0.6.200을 썼다. 0.6.200은 SDK 본체(`libleiaSDK.so`)와 셰이더 17개를 앱이 통째로 들고 있어야 했다. 0.8.x는 구조가 다르다. 앱에는 얇은 JNI와 로더만 들어가고, 본체는 **기기의 시스템 앱 `com.leialoft.display.config`에서 불러온다.** Lume Pad 2에 깔린 그 앱이 0.8.20이고, Leia가 EOL시켰으니 더 올라가지 않는다. 0.8.20은 LeiaTube APK에서 뽑았다.

```
app/libs/leia-cnsdk.jar                                   (150 클래스)
app/src/leia/jniLibs/arm64-v8a/libleiaSDK-jni.so          (6.6MB)
app/src/leia/jniLibs/arm64-v8a/libleiaCore-loader.so      (1.1MB)
app/src/leia/assets/cnsdk.version                         (0.8.20)
```

arm64만 있다. 셰이더도 없다. 위빙 셰이더는 실행 중에 기기의 인터레이싱 서비스에서 받아 온다. 정상이면 로그가 이렇게 나온다:

```
[Core-Loader] Successfully initialized in-service library
[Core] Initializing: app=0.8.20 > sdk=0.8.20.
[serv] received shader from the interlacing service
[HTS-Client] Server has been connected: serverInfo={.version=0.8.20, ...}
```

`JsonUtils failed to field "displayGeometryCPVM"` 같은 에러가 여러 줄 나오는데, 기기의 디스플레이 설정 JSON이 옛 형식(3.2)이라 없는 필드를 기본값으로 채우는 것이다. 무시해도 된다.

코드에서 바뀐 곳은 두 군데다:

- `Config.viewResolution` 필드가 `Config.getViewResolution()` 메서드로 바뀌었다.
- `LeiaSDK.Delegate`가 인터페이스에서 추상 클래스로 바뀌었고 `invalidLicense(LeiaSDK, String)`가 생겼다. 우리는 로그만 남긴다(`CNSDK refused its licence`). `InitArgs.enableValidation`이 기본 false라 실제로 불린 적은 없다.

0.6.200 파일(jar, arm64·v7a so, 셰이더 포함 assets) 백업은 개발 PC의 `D:\Claude Code\projects\cnsdk-backup\0.6.200`에 있다. 되돌리려면 위 파일을 지우고 백업을 같은 자리에 넣은 다음, 위 두 곳의 코드를 되돌린다.

---

## 4. 초기화에서 막히는 네 곳

전부 실기에서 하나씩 부딪힌 것들이다. 순서대로 해결해야 SDK가 뜬다.

### 4-1. 프로세스 즉사 — 패키지 가시성

CNSDK는 시작하면서 Leia 시스템 패키지 두 개를 조회한다. Android 11+의 패키지 가시성 제한으로 조회가 실패하는데 **예외를 지우지 않고** 다음 JNI 호출로 넘어가서 프로세스가 abort된다. 로그도 거의 안 남는다.

매니페스트에 선언하면 해결된다:

```xml
<queries>
    <package android:name="com.leia.headtrackingservice" />
    <package android:name="com.leialoft.display.config" />
</queries>
```

### 4-2. `createSDK`가 null 반환

`PlatformInitArgs`에 `app`만 넣으면 안 된다. **`activity`와 `context`까지 채워야** 한다:

```java
LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
args.platform = new PlatformInitArgs();
args.platform.app      = activity.getApplication();
args.platform.activity = activity;      // 없으면 null 반환
args.platform.context  = activity;      // 없으면 null 반환
args.enableFaceTracking = true;
args.delegate = delegate;
LeiaSDK sdk = LeiaSDK.createSDK(args);
```

### 4-3. "Leia 패널이 아님" 오판 — 비동기 초기화

`createSDK`가 돌아온 직후에 `isInitialized()`를 물으면 아직 false다. 우리 경우 77ms 뒤에 준비됐다. **`Delegate.didInitialize` 콜백을 기다려야 한다.**

```java
public interface LeiaSDK.Delegate {      // 0.8.x 에서는 abstract class, invalidLicense 추가 (3-1절)
    void didInitialize(LeiaSDK sdk);
    void onFaceTrackingStarted(LeiaSDK sdk);
    void onFaceTrackingStopped(LeiaSDK sdk);
    void onFaceTrackingFatalError(LeiaSDK sdk);
}
```

### 4-4. 로그가 안 보인다

`java.util.logging` 기반 로거(Moonlight의 `LimeLog` 등)는 이 기기 logcat에 나오지 않는다. **진단은 `android.util.Log`로 할 것.** 이걸 모르면 위 세 문제를 눈감고 더듬게 된다.

또 하나: 초당 수백 줄을 찍는 디버그 로그가 있으면 logcat 링 버퍼가 몇 초 만에 차서, 문제가 생긴 뒤에 읽으면 정작 필요한 줄이 이미 밀려나 있다. 프레임별 로그는 프로퍼티로 가둘 것.

```java
private static final boolean LOG_FRAME_TIMING =
        Log.isLoggable("MyTagTiming", Log.DEBUG);
// adb shell setprop log.tag.MyTagTiming DEBUG
```

---

## 5. API 요약

javap로 확인한 실제 시그니처(0.6.200 기준, 0.8.20에서 달라진 곳은 3-1절).

```java
// com.leia.sdk.LeiaSDK
static LeiaSDK createSDK(LeiaSDK.InitArgs) throws Exception;
static LeiaSDK getInstance();
static void    shutdownSDK();
static boolean isFaceTrackingInService();
void enableBacklight(boolean);
void startFaceTracking(boolean);

// com.leia.sdk.LeiaSDK$InitArgs
PlatformInitArgs platform;
LeiaSDK.Delegate delegate;
boolean enableFaceTracking;
boolean startFaceTracking;
boolean requiresFaceTrackingPermissionCheck;
boolean useSharedCameraSink;

// com.leia.core.PlatformInitArgs
Application app;  Activity activity;  Context context;  LogLevel logLevel;

// com.leia.sdk.views.InterlacedSurfaceView  (extends LeiaGLSurfaceView)
InterlacedSurfaceView(Context);
InterlacedSurfaceViewConfigAccessor getConfig();
void setViewAsset(InputViewsAsset);
void releaseInputViewsAsset();
void onResume();  void onPause();

// com.leia.sdk.views.InterlacedSurfaceViewConfigAccessor  (AutoCloseable)
void setNumTiles(int x, int y);      int getNumTilesX();  int getNumTilesY();
void setSourceSize(int w, int h);    Vector2i getSourceSize();
void setScaleType(ScaleType);

// com.leia.sdk.views.ScaleType
FIT_CENTER, FILL, CROP_FILL, CROP_FIT_SQUARE

// com.leia.sdk.views.InputViewsAsset
void CreateEmptySurfaceForVideo(int w, int h, SurfaceTextureReadyCallback);
```

`getConfig()`는 `AutoCloseable`이므로 try-with-resources로 감쌀 것. 닫아야 반영된다.

---

## 6. 최소 동작 코드

```java
// 1) SDK 띄우기 — didInitialize 를 기다린다
LeiaSDK.InitArgs args = new LeiaSDK.InitArgs();
args.platform = new PlatformInitArgs();
args.platform.app = activity.getApplication();
args.platform.activity = activity;
args.platform.context = activity;
args.enableFaceTracking = true;
args.delegate = new LeiaSDK.Delegate() {
    public void didInitialize(LeiaSDK s) { ready = true; applyState(); }
    public void onFaceTrackingStarted(LeiaSDK s) {}
    public void onFaceTrackingStopped(LeiaSDK s) {}
    public void onFaceTrackingFatalError(LeiaSDK s) {}
};
LeiaSDK sdk = LeiaSDK.createSDK(args);

// 2) 뷰와 디코더 서피스
InterlacedSurfaceView view = new InterlacedSurfaceView(context);
InputViewsAsset asset = new InputViewsAsset();
asset.CreateEmptySurfaceForVideo(frameWidth, frameHeight, st -> {
    st.setDefaultBufferSize(frameWidth, frameHeight);
    onSurfaceReady(new Surface(st));      // 여기에 디코더를 물린다
});
view.setViewAsset(asset);

// 3) SBS 로 알려주기
try (InterlacedSurfaceViewConfigAccessor c = view.getConfig()) {
    c.setSourceSize(frameWidth, frameHeight);   // 두 눈이 담긴 전체 프레임
    c.setNumTiles(2, 1);                        // 좌우로 가른다
    c.setScaleType(ScaleType.FIT_CENTER);
}

// 4) 백라이트와 얼굴추적은 화면에 떠 있을 때만
sdk.startFaceTracking(active);
sdk.enableBacklight(active);
```

---

## 7. 기하 — 여기서 제일 많이 헤맸다

### `setSourceSize`는 두 눈이 담긴 전체 프레임 크기다

`numTiles(2,1)`이 그것을 반으로 나눈다. 1920×1200 영상을 눈당 온전한 해상도로 보내려면 프레임은 **3840×1200**이고 `setSourceSize(3840, 1200)`이다.

### half-SBS 로 보내면 눌린다

프레임을 1920×1200으로 두고 그 안에 두 눈을 넣으면 눈당 960×1200이 되고, 16:10 장면이 4:5 타일에 들어가 가로로 압축된다. CNSDK는 원래 장면이 어떤 비율이었는지 알 방법이 없다. **full-SBS(가로 2배)로 보낼 것.**

### `ScaleType`은 타일 하나 단위로 적용된다

> **정정 (2026-09-13).** 이 절은 처음에 "`FIT_CENTER`를 설정해도 실제로는 화면을 채운다"고
> 적었는데 틀린 결론이었다. 변환 경로에서는 타일(2560×1600)이 우연히 패널과 같은 비율이라
> 맞춤과 채움이 구분되지 않았을 뿐이다.

`FIT_CENTER`는 **타일 하나를 그 비율 그대로** 패널에 맞춘다. 문제는 SBS 타일의 비율이 장면의
비율이 아니라는 것이다. half-SBS는 두 눈을 한 프레임에 넣으려고 각 눈을 가로로 절반 압축해 둔다.

```
2560×1600 half-SBS 스트림, tiles=2×1, FIT_CENTER
  → 타일 1280×1600 (0.8) 을 비율 그대로 맞춤
  → 패널 가운데 1280px 폭에만 그려지고 양옆은 검정
  → 좌우가 눌려 보이고, 절반이 검어서 화면 전체가 어둡다
```

**가르는 타일은 `FILL`로 늘려야 한다** — 그게 압축을 되돌린다. 평면(1×1)일 때는 `FIT_CENTER`로 둬야
16:9 스트림이 16:10 패널에서 늘어나지 않는다. 우리는 `applyTiling()`에서 이렇게 한다:

```java
config.setScaleType(split ? ScaleType.FILL : ScaleType.FIT_CENTER);
```

2D→3D 변환 프레임은 이미 패널 모양으로 만들어 넘기므로(아래) 어느 쪽이든 결과가 같다.

해결책은 **넘기기 전에 프레임을 패널 모양으로 만드는 것**이다. 눈당 상자를 패널 종횡비로 잡고 영상을 그 안에 중앙 배치한 뒤 나머지를 검게 칠한다. 그러면 CNSDK가 무엇을 하든 이미 올바른 모양이라 늘어날 여지가 없다.

```
1920×1080 (16:9) 입력, 패널 16:10
  → 눈당 1920×1200 상자, 영상은 세로 중앙에 1920×1080, 위아래 60px 검정
  → setSourceSize(3840, 1200), setNumTiles(2, 1)
```

### 소유권을 한 곳에 둘 것

`setSourceSize`를 여러 곳에서 부르면 나중 호출이 앞의 것을 덮는다. 우리는 화면 크기만 아는 코드가 SBS 크기를 덮어써서 한참 헤맸다. **프레임에 눈이 하나 담기는지 둘 담기는지 아는 쪽이 소유해야 한다.**

---

## 8. 권장 해상도

패널이 보고한 View Resolution이 **눈당 1920×1200**이다. 그보다 올리면 패널이 표현 못 하는 픽셀에 대역폭·연산을 쓰는 셈이고, 낮추면 디테일이 부족하다. 16:10이라 패널 종횡비와도 맞는다.

| 눈당 | SBS 프레임 | 비고 |
|---|---|---|
| 1920×1200 | 3840×1200 | View Resolution 과 일치 — 권장 |
| 2560×1600 | 5120×1600 | 패널 네이티브. 처리 부담 큼 |
| 1280×800 | 2560×800 | 가벼움, 화질 아쉬움 |
| 1280×720 | 2560×720 | 16:9 라 레터박스 필요 |

---

## 9. 생명주기

백라이트와 카메라는 시스템 공용이다. **앱이 앞에 있고 3D일 때만** 잡고 있어야 한다.

```java
@Override protected void onPause() {
    sdk.startFaceTracking(false);
    sdk.enableBacklight(false);
    view.onPause();
}
@Override protected void onResume() {
    view.onResume();
    // 3D 상태면 다시 켠다
}
```

확인해 보면 홈으로 나갈 때 `Face tracking stopped`가 뜨고 타일이 1×1로 돌아가야 정상이다. 안 그러면 다음 앱이 3D 상태로 남은 패널을 물려받는다.

`InterlacedSurfaceView`가 아닌 **별도의 `GLSurfaceView`를 숨겨두고 쓰는 구조라면 그쪽 `onResume`/`onPause`도 직접 불러줘야 한다.** 화면에 안 보인다고 시스템이 대신 해주지 않는다. 빠뜨리면 백그라운드에서 GL 스레드가 계속 돌고 복귀 시 컨텍스트를 잃는다.

---

## 9-1. 얼굴추적이 안 붙을 때

위빙이 정상인데도 평면으로 보이면 십중팔구 여기다. 화면에는 시차만큼 어긋난 상이 겹쳐 찍히므로
스크린샷만 보면 정상으로 보인다 — 눈에 3D 로 오려면 패널이 시청자 위치를 알아야 한다.

확인은 두 줄이면 된다.

```bash
adb logcat -d | grep -i "Face tracking started"
adb shell dumpsys media.camera | grep -A2 "Active Camera Clients"
```

`Active Camera Clients: []` 로 비어 있으면 추적이 카메라를 못 잡은 것이다.

**원인 1 — 다른 Leia 앱이 쥐고 있다.** 헤드트래킹 서비스와 카메라는 기기 공용이고, 한 번에 하나만
쓴다. LeiaPlayer 나 다른 3D 앱이 떠 있으면 우리 쪽은 영영 못 붙는다. 로그에는 아무 오류도 남지
않고 그냥 `Face tracking started` 가 안 올 뿐이라, 코드를 의심하며 한참 헤매게 된다.

```bash
adb shell am force-stop <경쟁 앱>
```

**원인 2 — 서비스가 유휴로 죽는다.** Android 가 알아서 정리한다.

```
W/ActivityManager: Stopping service due to app idle: com.leia.headtrackingservice
I/ActivityManager: Process com.leia.headtrackingservice... has died
```

곧 다시 뜨지만 그 사이에 시작한 스트림은 추적 없이 돌아간다.

**원인 3 — NoFaceMode.** 이건 추적이 붙었다가 놓칠 때의 이야기다. 4-4 아래 참고: 기본값이 얼굴을
놓치면 3D 백라이트를 꺼버리는 것이라 `enableNoFaceMode(false)` 로 꺼야 한다.

> 추적이 안 붙는다고 껐다 켜기를 반복하지 말 것. 서비스가 카메라를 잡을 틈이 없어져 더 안 붙는다.
> 경쟁 앱을 정리하고 한 번 새로 시작하는 편이 빠르다.

---

## 10. CNSDK 자체 변환은 쓸 수 있나 — 부분적으로만

SDK에 2D→3D 변환 API가 있다. 기대할 만한 이름인데, 실제로 동작하는 것과 껍데기인 것이 갈린다.

```java
// com.leia.sdk.LeiaSDK$ML  (sdk.getML() 로 얻는다)
public native Bitmap Convert(Bitmap, int);                    // ← 동작한다
public MonoVideoMLMethods CreateMonoVideoML(Context);         // ← null 을 돌려준다
```

### 정지 이미지 — 동작한다

`Convert`는 네이티브 구현이 라이브러리에 실제로 들어 있다:

```
libleiaSDK.so 에 Java_com_leia_sdk_LeiaSDK_00024ML_Convert 심볼 존재
```

Bitmap 하나를 넣고 변환된 Bitmap을 받는 형태다. 두 번째 `int` 인자의 의미는 확인하지 못했다.

### 영상 — 구현되어 있지 않다

`CreateMonoVideoML`은 `MonoVideoMLFactory.Create`로 넘기는데, 그 메서드의 바이트코드가 이게 전부다:

```
0: aconst_null
1: areturn          // 즉 return null;
```

인터페이스(`MonoVideoMLMethods`, `MonoVideoMLCallback`)는 정의돼 있으나 팩토리가 항상 null을 반환한다. **정식 Moonlight3D에 들어 있는 이 빌드에서는 영상 변환을 쓸 수 없다.** Leia가 라이선스 배포판에서 채워주는 부분이거나, 다른 경로로 제공하는 것으로 보인다.

참고로 형태는 이렇게 잡혀 있다. 나중에 동작하는 빌드를 구하면 그대로 쓸 수 있다:

```java
public interface MonoVideoMLMethods {
    void setCallback(MonoVideoMLCallback);
    void onSurfaceTextureReady(SurfaceTexture);
    void close();
}
public interface MonoVideoMLCallback {
    void onConfigAndPlay(SurfaceTexture in, SurfaceTexture out, int w, int h);
}
```

### 변환이 어디서 도는가

`libleiaSDK.so`가 참조하는 외부 라이브러리에 ML 런타임이 없다:

```
libEGL, libGLESv3, libvulkan, libandroid, libbinder_ndk, libjnigraphics, liblog
```

TFLite도 SNPE도 ONNX도 없고 대신 `libbinder_ndk`가 있다. 무거운 연산은 **기기의 Leia 시스템 서비스로 IPC 위임**하는 구조로 보인다. 모델 파일을 앱에 넣을 필요는 없지만, 그만큼 Leia 기기 밖에서는 성립하지 않는다.

---

## 10-1. 직접 변환할 경우

CNSDK 영상 변환을 못 쓰므로, 2D 영상을 3D로 보려면 직접 만들어야 한다. Artemis가 쓰는 방식이 참고가 된다.


- **모델**: `midas-midas-v2-w8a8.tflite` (MiDaS v2, int8, 입력 256×256). **Intel 공개 모델이라 라이선스 문제 없다.** ONNX/PyTorch 버전도 공개돼 있어 다른 플랫폼 이식도 가능하다.
- **성능**: Lume Pad 2에서 깊이 추정 6~9ms/프레임. 60fps 여유 있음.
- **경로**: 디코더 → SurfaceTexture → 깊이 추정 → DIBR 셰이더로 좌우 눈 생성 → SBS

`GLSurfaceView`에서 만든 프레임을 CNSDK 서피스로 보내려면 같은 EGL 컨텍스트에 두 번째 EGL 서피스를 만들어 프레임마다 전환한다. 주의할 점: `GLSurfaceView`는 **EGL10**으로 컨텍스트를 만드는데 윈도우 서피스는 **EGL14**로 만들게 된다. 윈도우 서피스는 컨텍스트와 같은 config에서만 동작하므로, config를 새로 고르지 말고 id로 되찾아와야 한다.

```java
int[] id = new int[1];
EGL14.eglQueryContext(display, context, EGL14.EGL_CONFIG_ID, id, 0);
int[] attrs = {EGL14.EGL_CONFIG_ID, id[0], EGL14.EGL_NONE};
EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, found, 0);
```

그리고 `EGLConfig`는 `javax.microedition.khronos.egl`과 `android.opengl` 양쪽에 있다. `GLSurfaceView.Renderer`를 구현하면 전자가 이미 임포트돼 있으므로 후자는 정규화된 이름으로 쓸 것.

---

## 11. 증상 → 원인 대응표

실제로 겪은 것들. 코드를 뒤지기 전에 여기부터 볼 것.

| 증상 | 원인 |
|---|---|
| 앱이 즉시 죽음, 로그 없음 | 패키지 가시성 (4-1) |
| `createSDK` 가 null | `InitArgs.platform.activity/context` 누락 (4-2) |
| "Leia 패널 아님" 으로 분기 | 비동기 초기화를 안 기다림 (4-3) |
| 아무 로그도 안 보임 | `LimeLog` 계열은 logcat 에 안 나감 (4-4) |
| 화면이 가로 절반에 눌림 | half-SBS 로 보냄 (7) |
| SBS 가 가운데 절반에만 나옴, 좌우 눌림, 어두움 | 가른 타일에 `FIT_CENTER` (7) |
| 세로로 들고 시작하면 깨짐 | 회전 의존 종횡비 계산 (1) |
| 설정한 크기가 무시됨 | `setSourceSize` 를 다른 곳에서 덮어씀 (7) |
| 3D 가 간헐적으로 안 됨 | 위 항목들이 조건부로 걸린 경우가 많음 |
| `CreateMonoVideoML` 이 null | 이 빌드에 구현이 없다 (10절) |
| 위빙은 되는데 눈에는 평면 | 얼굴추적이 안 붙었다 (9-1) |
| 3D 가 잠깐씩 풀린다 | NoFaceMode 가 켜져 있다 (9-1) |

`E/LeiaSDK: [JsonUtils] failed to get "displayGeometryCPVM"` 는 매 실행 뜨지만 무해해 보인다. 동작에 영향 없었다.

---

## 12. 확인에 쓴 명령

```bash
# 패널 설정 읽기
adb shell dumpsys display | grep -i leia

# 우리 태그만 보기 (연속 캡처 — 사후에 읽으면 이미 밀려나 있다)
adb logcat -v time PanelDriver:V LeiaSDK:E AndroidRuntime:E "*:S" > run.log &

# 화면 캡처 (무선이면 exec-out 이 안정적)
adb exec-out screencap -p > shot.png

# 앱 설정 직접 보기/바꾸기 (앱은 정지 상태여야 함)
adb shell run-as <pkg> cat shared_prefs/<pkg>_preferences.xml
```

스크린샷으로 위빙 여부를 판별할 수 있다. **위빙 중이면 시차만큼 어긋난 상이 겹쳐 보이고, 단일 뷰만 나가면 깨끗하게 찍힌다.** 깊이감 자체는 눈으로만 판단된다.
