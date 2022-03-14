package com.example.voiceassisant1;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ListView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.google.gson.Gson;
import com.iflytek.cloud.ErrorCode;
import com.iflytek.cloud.InitListener;
import com.iflytek.cloud.RecognizerListener;
import com.iflytek.cloud.RecognizerResult;
import com.iflytek.cloud.SpeechConstant;
import com.iflytek.cloud.SpeechError;
import com.iflytek.cloud.SpeechEvent;
import com.iflytek.cloud.SpeechRecognizer;
import com.iflytek.cloud.SpeechSynthesizer;
import com.iflytek.cloud.SpeechUtility;
import com.iflytek.cloud.SynthesizerListener;
import com.iflytek.cloud.ui.RecognizerDialog;
import com.iflytek.cloud.ui.RecognizerDialogListener;
import com.qweather.sdk.bean.base.Code;
import com.qweather.sdk.bean.base.Lang;
import com.qweather.sdk.bean.base.Unit;
import com.qweather.sdk.bean.geo.GeoBean;
import com.qweather.sdk.bean.weather.WeatherNowBean;
import com.qweather.sdk.view.HeConfig;
import com.qweather.sdk.view.QWeather;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

public class MainActivity extends Activity implements View.OnClickListener {

    private ListView listView;
    private List<ListData> lists;
    private TextAdapter adapter;
    private ListData listData;
    private String resultText;
    private String[] welcome_array;
    private String speakText;
    private String locationId;
    private boolean isMessage = false;
    private String msg_num;
    private String msg_name;
    private String city;
    private String cityId;

    private static String TAG = MainActivity.class.getSimpleName();
    private SpeechRecognizer mIat;
    private RecognizerDialog mIatDialog;
    private HashMap<String, String> mIatResults = new LinkedHashMap<>();
    private Toast mToast;
    private SharedPreferences mSharedPreferences;
    private String mEngineType = SpeechConstant.TYPE_CLOUD;
    private String language = "zh_cn";
    private String resultType = "json";
    private StringBuffer buffer = new StringBuffer();
    int ret = 0;

    // 语音合成对象
    private SpeechSynthesizer mTts;
    // 默认发音人
    private String voicer = "xiaoyan";
    private File pcmFile;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SpeechUtility.createUtility(MainActivity.this, "appid=" + getString(R.string.app_id));
        requestPermissions();
        // 使用SpeechRecognizer对象，可根据回调消息自定义界面；
        mIat = SpeechRecognizer.createRecognizer(MainActivity.this, mInitListener);
        // 初始化听写Dialog，如果只使用有UI听写功能，无需创建SpeechRecognizer
        // 使用UI听写功能，请根据sdk文件目录下的notice.txt,放置布局文件和图片资源
        mIatDialog = new RecognizerDialog(MainActivity.this, mInitListener);

        mSharedPreferences = getSharedPreferences(IatSettings.PREFER_NAME,
                Activity.MODE_PRIVATE);

        // 初始化合成对象
        mTts = SpeechSynthesizer.createSynthesizer(MainActivity.this, mTtsInitListener);
        mSharedPreferences = getSharedPreferences(TtsSettings.PREFER_NAME, MODE_PRIVATE);

        initGetWeather();
        getLocation();
        initLayout();
    }

    private void initLayout() {
        findViewById(R.id.iat_recognize).setOnClickListener(MainActivity.this);
        listView = (ListView) findViewById(R.id.listView);
        lists = new ArrayList<ListData>();
        adapter = new TextAdapter(lists, this);
        listView.setAdapter(adapter);
        speakText = getRandomWelcomeTips();
        refresh(speakText, ListData.RECEIVER);
        speak(speakText);
    }

    @Override
    public void onClick(View view) {
        if (null == mIat) {
            // 创建单例失败，与 21001 错误为同样原因，参考 http://bbs.xfyun.cn/forum.php?mod=viewthread&tid=9688
            this.showTip("创建对象失败，请确认 libmsc.so 放置正确，且有调用 createUtility 进行初始化");
            return;
        }

        if (null == mTts) {
            // 创建单例失败，与 21001 错误为同样原因，参考 http://bbs.xfyun.cn/forum.php?mod=viewthread&tid=9688
            this.showTip("创建对象失败，请确认 libmsc.so 放置正确，且有调用 createUtility 进行初始化");
            return;
        }

        switch (view.getId()) {
            // 开始听写
            // 如何判断一次听写结束：OnResult isLast=true 或者 onError
            case R.id.iat_recognize:
                startToSpeak();
                break;
            default:
                break;
        }
    }

    /**
     * 初始化监听器。
     */
    private InitListener mInitListener = new InitListener() {

        @Override
        public void onInit(int code) {
            Log.d(TAG, "SpeechRecognizer init() code = " + code);
            if (code != ErrorCode.SUCCESS) {
                showTip("初始化失败，错误码：" + code + ",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
            }
        }
    };

    /**
     * 听写监听器。
     */
    private RecognizerListener mRecognizerListener = new RecognizerListener() {

        @Override
        public void onBeginOfSpeech() {
            // 此回调表示：sdk内部录音机已经准备好了，用户可以开始语音输入
            showTip("开始说话");
        }

        @Override
        public void onError(SpeechError error) {
            // Tips：
            // 错误码：10118(您没有说话)，可能是录音机权限被禁，需要提示用户打开应用的录音权限。
            Log.d(TAG, "onError " + error.getPlainDescription(true));
            showTip(error.getPlainDescription(true));

        }

        @Override
        public void onEndOfSpeech() {
            // 此回调表示：检测到了语音的尾端点，已经进入识别过程，不再接受语音输入
            showTip("结束说话");
        }

        @Override
        public void onResult(RecognizerResult results, boolean isLast) {
            Log.d(TAG, results.getResultString());
            if (isLast) {
                Log.d(TAG, "onResult 结束");
                printResult(results);
            }
            if (resultType.equals("json")) {
                //printResult(results);
                return;
            }
            if (resultType.equals("plain")) {
                buffer.append(results.getResultString());
                //mResultText.setText(buffer.toString());
                //mResultText.setSelection(mResultText.length());
            }
        }

        @Override
        public void onVolumeChanged(int volume, byte[] data) {
            showTip("当前正在说话，音量大小 = " + volume + " 返回音频数据 = " + data.length);
        }

        @Override
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
            // 以下代码用于获取与云端的会话id，当业务出错时将会话id提供给技术支持人员，可用于查询会话日志，定位出错原因
            // 若使用本地能力，会话id为null
            //	if (SpeechEvent.EVENT_SESSION_ID == eventType) {
            //		String sid = obj.getString(SpeechEvent.KEY_EVENT_SESSION_ID);
            //		Log.d(TAG, "session id =" + sid);
            //	}
        }
    };

    /**
     * 听写UI监听器
     */
    private RecognizerDialogListener mRecognizerDialogListener = new RecognizerDialogListener() {
        // 返回结果
        public void onResult(RecognizerResult results, boolean isLast) {
            printResult(results);
        }

        // 识别回调错误
        public void onError(SpeechError error) {
            showTip(error.getPlainDescription(true));
        }

    };

    /**
     * 显示结果  判断语义
     */
    private void printResult(RecognizerResult results) {
        String text = JsonParser.parseIatResult(results.getResultString());
        mIat.stopListening();
        if(TextUtils.isEmpty(text)){
            return;
        }
        String sn = null;
        // 读取json结果中的sn字段
        try {
            JSONObject resultJson = new JSONObject(results.getResultString());
            sn = resultJson.optString("sn");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        mIatResults.put(sn, text);

        StringBuffer resultBuffer = new StringBuffer();
        for (String key : mIatResults.keySet()) {
            resultBuffer.append(mIatResults.get(key));
        }
        //mResultText.setText(resultBuffer.toString());
        //mResultText.setSelection(mResultText.length());
        resultText = text;
        refresh(text, ListData.SEND);

        service(resultText);

    }

    //服务判断
    private void service(String result) {

        if (isMessage){

            sendMessage(result);
            isMessage = false;

        } else if (result.contains("打电话")) {

            call();

        } else if (result.contains("发短信")){

            getSendMsgContactInfo();

        } else if (result.contains("天气")) {

            city = result.substring(0,resultText.indexOf("天"));
            getGeoInfo(city);

        } else if (result.contains("查找")){

            String searchText = result.substring(resultText.indexOf("找")+1);
            surfTheInternet(searchText);

        } else if (result.contains("查一下")){

            String searchText = result.substring(resultText.indexOf("下")+1);
            surfTheInternet(searchText);

        }
    }

    //录音
    private void startToSpeak(){
        buffer.setLength(0);
        //mResultText.setText(null);// 清空显示内容
        mIatResults.clear();
        // 设置参数
        setParam();
        boolean isShowDialog = mSharedPreferences.getBoolean(
                getString(R.string.pref_key_iat_show), true);
        if (isShowDialog) {
            // 显示听写对话框
            mIatDialog.setListener(mRecognizerDialogListener);
            mIatDialog.show();
            showTip(getString(R.string.text_begin));
        } else {
            // 不显示听写对话框
            ret = mIat.startListening(mRecognizerListener);
            if (ret != ErrorCode.SUCCESS) {
                showTip("听写失败,错误码：" + ret + ",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
            } else {
                showTip(getString(R.string.text_begin));
            }
        }
    }

    //随机获取欢迎语
    private String getRandomWelcomeTips() {
        String welcome_tip = null;
        welcome_array = this.getResources()
                .getStringArray(R.array.welcome_tips);
        int index = (int) (Math.random() * (welcome_array.length - 1));
        welcome_tip = welcome_array[index];
        return welcome_tip;
    }

    //获取通信录联系人
    private List<ContactInfo> getContactLists(Context context) {
        List<ContactInfo> lists = new ArrayList<ContactInfo>();
        Cursor cursor = context.getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null);
        //moveToNext方法返回的是一个boolean类型的数据
        while (cursor.moveToNext()) {
            //读取通讯录的姓名
            @SuppressLint("Range") String name = cursor.getString(cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
            //读取通讯录的号码
            @SuppressLint("Range") String number = cursor.getString(cursor
                    .getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER));
            ContactInfo contactInfo = new ContactInfo(name, number);
            lists.add(contactInfo);
        }
        return lists;
    }

    //打电话
    private void call() {
        List<ContactInfo> contactLists = getContactLists(this);
        if (contactLists.isEmpty()) {
            speakText = "通讯录为空";
            refresh(speakText, ListData.RECEIVER);
            speak(listData.getContent());
            return;
        }
        for (ContactInfo contactInfo : contactLists) {
            if (resultText.contains(contactInfo.getName())) {
                speakText = "已为您拨通" + contactInfo.getName() + "的电话";
                refresh(speakText, ListData.RECEIVER);
                speak(listData.getContent());

                String number = contactInfo.getNumber();
                Intent intent = new Intent();
                intent.setAction("android.intent.action.CALL");
                intent.addCategory(Intent.CATEGORY_DEFAULT);
                intent.setData(Uri.parse("tel:" + number));
                startActivity(intent);
                return;
            }
        }
        speakText = "通讯录中查无此人";
        refresh(speakText, ListData.RECEIVER);
        speak(listData.getContent());
    }

    //短信内容
    private void sendMessage(String content) {
        if (msg_num==null){
            return;
        }
        SmsManager smsManager = android.telephony.SmsManager
                .getDefault();
        ArrayList<String> divideContents = smsManager.divideMessage(content);  //因为一条短信有字数限制，因此要将长短信拆分
        for(String text:divideContents){
            smsManager.sendTextMessage(msg_num, null, text, null, null);
        }
        refresh("已发送给"+msg_name,ListData.RECEIVER);
        speak(listData.getContent());
    }

    //发短信联系人
    private void getSendMsgContactInfo() {
        List<ContactInfo> contactLists = getContactLists(this);
        if (contactLists.isEmpty()){
            refresh("通讯录为空",ListData.RECEIVER);
            speak(listData.getContent());
            return;
        }
        for (ContactInfo contactInfo:contactLists){
            if (resultText.contains(contactInfo.getName())){
                msg_name=contactInfo.getName();
                msg_num=contactInfo.getNumber();
                refresh("请说明发送内容",ListData.RECEIVER);
                speak(listData.getContent());
                isMessage = true;
                startToSpeak();
                return;
            }
        }
        refresh("通讯录中查无此人",ListData.RECEIVER);
        speak(listData.getContent());
    }

    //初始化天气查询
    private void initGetWeather() {
        HeConfig.init("HE2203132007451554", "c156847e9a484c8e815189ec5afc2940");
        HeConfig.switchToDevService();
    }

    //定位
    private void getLocation() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        String provider = LocationManager.NETWORK_PROVIDER;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        Location location = locationManager.getLastKnownLocation(provider);
        double Longitude = location.getLongitude();
        double Latitude = location.getLatitude();
        DecimalFormat df = new DecimalFormat("#.00");
        locationId = df.format(Longitude) + "," + df.format(Latitude);
    }

    //获取天气信息
    private void getWeatherInfo(String id) {
        /**
         * 实况天气数据
         * @param location 所查询的地区，可通过该地区ID、经纬度进行查询经纬度格式：经度,纬度
         *                 （英文,分隔，十进制格式，北纬东经为正，南纬西经为负)
         * @param lang     (选填)多语言，可以不使用该参数，默认为简体中文
         * @param unit     (选填)单位选择，公制（m）或英制（i），默认为公制单位
         * @param listener 网络访问结果回调
         */
        QWeather.getWeatherNow(MainActivity.this, id, Lang.ZH_HANS, Unit.METRIC, new QWeather.OnResultWeatherNowListener() {
            @Override
            public void onError(Throwable e) {
                Log.i(TAG, "getWeather onError: " + e);
            }

            @Override
            public void onSuccess(WeatherNowBean weatherBean) {
                Log.i(TAG, "getWeather onSuccess: " + new Gson().toJson(weatherBean));
                //先判断返回的status是否正确，当status正确时获取数据，若status不正确，可查看status对应的Code值找到原因
                if (Code.OK == weatherBean.getCode()) {
                    WeatherNowBean.NowBaseBean now = weatherBean.getNow();
                    String weather = now.getText();
                    String temp = now.getTemp();
                    String windDir = now.getWindDir();

                    resultText = city + "市天气" + weather + ",室外" + temp + "摄氏度," + "有" + windDir + ",";
                    refresh(resultText, ListData.RECEIVER);
                    speak(resultText);
                } else {
                    //在此查看返回数据失败的原因
                    Code code = weatherBean.getCode();
                    Log.i(TAG, "failed code: " + code);
                }
            }
        });
    }

    //获取地理信息
    private void getGeoInfo(String cs){
        QWeather.getGeoCityLookup(MainActivity.this, cs, new QWeather.OnResultGeoListener() {
            @Override
            public void onError(Throwable throwable) {
                //Log.i(TAG, "getGeoInfo onError: " + throwable);
                //获取city为空，默认为当前城市
                getGeoInfo(locationId);
            }

            @Override
            public void onSuccess(GeoBean geoBean) {
                Log.i(TAG, "getGeoInfo onSuccess: " + new Gson().toJson(geoBean));
                if (Code.OK == geoBean.getCode()){
                    List<GeoBean.LocationBean> locationBeans = geoBean.getLocationBean();
                    city = locationBeans.get(0).getAdm2();
                    System.out.println(city);
                    cityId = locationBeans.get(0).getId();
                    getWeatherInfo(cityId);
                } else {
                    Code code = geoBean.getCode();
                    Log.i(TAG, "failed code:" + code);
                }
            }
        });
    }

    //网页查找
    private void surfTheInternet(String searchContent) {
        refresh("已上网查找"+"\""+searchContent+"\"",ListData.RECEIVER);
        speak(listData.getContent());

        // 指定intent的action是ACTION_WEB_SEARCH就能调用浏览器
        Intent intent = new Intent(Intent.ACTION_WEB_SEARCH);
        // 指定搜索关键字是选中的文本
        intent.putExtra(SearchManager.QUERY, searchContent);
        startActivity(intent);
    }

    //语音合成
    public void speak(String text) {
        pcmFile = new File(getExternalCacheDir().getAbsolutePath(), "tts_pcmFile.pcm");
        pcmFile.delete();
        setParam();
        int code = mTts.startSpeaking(text, mTtsListener);
        if (code != ErrorCode.SUCCESS) {
            showTip("语音合成失败,错误码: " + code + ",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
        }
    }

    //刷新页面
    private void refresh(String content, int flag) {
        listData = new ListData(content, flag);
        lists.add(listData);
//        如果item数量大于30，清空数据
//        if (lists.size() > 30) {
//            for (int i = 0; i < lists.size(); i++) {
//                // 移除数据
//                lists.remove(i);
//            }
//        }
        adapter.notifyDataSetChanged();
    }

    /**
     * 初始化监听。
     */
    private InitListener mTtsInitListener = new InitListener() {
        @Override
        public void onInit(int code) {
            Log.d(TAG, "InitListener init() code = " + code);
            if (code != ErrorCode.SUCCESS) {
                showTip("初始化失败,错误码：" + code + ",请点击网址https://www.xfyun.cn/document/error-code查询解决方案");
            } else {
                // 初始化成功，之后可以调用startSpeaking方法
                // 注：有的开发者在onCreate方法中创建完合成对象之后马上就调用startSpeaking进行合成，
                // 正确的做法是将onCreate中的startSpeaking调用移至这里
            }
        }
    };

    /**
     * 合成回调监听。
     */
    private SynthesizerListener mTtsListener = new SynthesizerListener() {

        @Override
        public void onSpeakBegin() {
            //showTip("开始播放");
        }

        @Override
        public void onSpeakPaused() {
            //showTip("暂停播放");
        }

        @Override
        public void onSpeakResumed() {
            //showTip("继续播放");
        }

        @Override
        public void onBufferProgress(int percent, int beginPos, int endPos,
                                     String info) {
            // 合成进度
            Log.e("MscSpeechLog_", "percent =" + percent);
            //mPercentForBuffering = percent;
//            showTip(String.format(getString(R.string.tts_toast_format),
//                    mPercentForBuffering, mPercentForPlaying));
        }

        @Override
        public void onSpeakProgress(int percent, int beginPos, int endPos) {
            // 播放进度
//            Log.e("MscSpeechLog_", "percent =" + percent);
//            mPercentForPlaying = percent;
//            showTip(String.format(getString(R.string.tts_toast_format),
//                    mPercentForBuffering, mPercentForPlaying));
//
//            SpannableStringBuilder style = new SpannableStringBuilder(texts);
//            Log.e(TAG, "beginPos = " + beginPos + "  endPos = " + endPos);
//            style.setSpan(new BackgroundColorSpan(Color.RED), beginPos, endPos, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            ((EditText) findViewById(R.id.tts_text)).setText(style);
        }

        @Override
        public void onCompleted(SpeechError error) {
            //showTip("播放完成");
            if (error != null) {
                showTip(error.getPlainDescription(true));
                return;
            }
        }

        @Override
        public void onEvent(int eventType, int arg1, int arg2, Bundle obj) {
            //	 以下代码用于获取与云端的会话id，当业务出错时将会话id提供给技术支持人员，可用于查询会话日志，定位出错原因
            //	 若使用本地能力，会话id为null
            if (SpeechEvent.EVENT_SESSION_ID == eventType) {
                String sid = obj.getString(SpeechEvent.KEY_EVENT_SESSION_ID);
                Log.d(TAG, "session id =" + sid);
            }
            // 当设置 SpeechConstant.TTS_DATA_NOTIFY 为1时，抛出buf数据
            if (SpeechEvent.EVENT_TTS_BUFFER == eventType) {
                byte[] buf = obj.getByteArray(SpeechEvent.KEY_EVENT_TTS_BUFFER);
                Log.e(TAG, "EVENT_TTS_BUFFER = " + buf.length);
                // 保存文件
                appendFile(pcmFile, buf);
            }

        }
    };

    /**
     * 给file追加数据
     */
    private void appendFile(File file, byte[] buffer) {
        try {
            if (!file.exists()) {
                boolean b = file.createNewFile();
            }
            RandomAccessFile randomFile = new RandomAccessFile(file, "rw");
            randomFile.seek(file.length());
            randomFile.write(buffer);
            randomFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void showTip(final String str) {
        if (mToast != null) {
            mToast.cancel();
        }
        mToast = Toast.makeText(getApplicationContext(), str, Toast.LENGTH_SHORT);
        mToast.show();
    }

    private void requestPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.CAMERA,
                        Manifest.permission.READ_CONTACTS,
                        Manifest.permission.CALL_PHONE,
                        Manifest.permission.SEND_SMS,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                }, 0x0010);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    /**
     * 参数设置
     *
     * @return
     */
    public void setParam() {
        // 清空参数
        mIat.setParameter(SpeechConstant.PARAMS, null);
        // 设置听写引擎
        mIat.setParameter(SpeechConstant.ENGINE_TYPE, mEngineType);
        // 设置返回结果格式
        mIat.setParameter(SpeechConstant.RESULT_TYPE, resultType);

        if (language.equals("zh_cn")) {
            String lag = mSharedPreferences.getString("iat_language_preference",
                    "mandarin");
            // 设置语言
            Log.e(TAG, "language = " + language);
            mIat.setParameter(SpeechConstant.LANGUAGE, "zh_cn");
            // 设置语言区域
            mIat.setParameter(SpeechConstant.ACCENT, lag);
        } else {
            mIat.setParameter(SpeechConstant.LANGUAGE, language);
        }
        Log.e(TAG, "last language:" + mIat.getParameter(SpeechConstant.LANGUAGE));

        //此处用于设置dialog中不显示错误码信息
        //mIat.setParameter("view_tips_plain","false");

        // 设置语音前端点:静音超时时间，即用户多长时间不说话则当做超时处理
        mIat.setParameter(SpeechConstant.VAD_BOS, mSharedPreferences.getString("iat_vadbos_preference", "3000"));

        // 设置语音后端点:后端点静音检测时间，即用户停止说话多长时间内即认为不再输入， 自动停止录音
        mIat.setParameter(SpeechConstant.VAD_EOS, mSharedPreferences.getString("iat_vadeos_preference", "1000"));

        // 设置标点符号,设置为"0"返回结果无标点,设置为"1"返回结果有标点
        mIat.setParameter(SpeechConstant.ASR_PTT, mSharedPreferences.getString("iat_punc_preference", "0"));

        // 设置音频保存路径，保存音频格式支持pcm、wav.
        mIat.setParameter(SpeechConstant.AUDIO_FORMAT, "wav");
        mIat.setParameter(SpeechConstant.ASR_AUDIO_PATH,
                getExternalFilesDir("msc").getAbsolutePath() + "/iat.wav");

        // 根据合成引擎设置相应参数
        if (mEngineType.equals(SpeechConstant.TYPE_CLOUD)) {
            mTts.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_CLOUD);
            // 支持实时音频返回，仅在 synthesizeToUri 条件下支持
            mTts.setParameter(SpeechConstant.TTS_DATA_NOTIFY, "1");
            //	mTts.setParameter(SpeechConstant.TTS_BUFFER_TIME,"1");

            // 设置在线合成发音人
            mTts.setParameter(SpeechConstant.VOICE_NAME, voicer);
            //设置合成语速
            mTts.setParameter(SpeechConstant.SPEED, mSharedPreferences.getString("speed_preference", "50"));
            //设置合成音调
            mTts.setParameter(SpeechConstant.PITCH, mSharedPreferences.getString("pitch_preference", "50"));
            //设置合成音量
            mTts.setParameter(SpeechConstant.VOLUME, mSharedPreferences.getString("volume_preference", "50"));
        } else {
            mTts.setParameter(SpeechConstant.ENGINE_TYPE, SpeechConstant.TYPE_LOCAL);
            mTts.setParameter(SpeechConstant.VOICE_NAME, "");

        }

        //设置播放器音频流类型
        mTts.setParameter(SpeechConstant.STREAM_TYPE, mSharedPreferences.getString("stream_preference", "3"));
        // 设置播放合成音频打断音乐播放，默认为true
        mTts.setParameter(SpeechConstant.KEY_REQUEST_FOCUS, "false");

        // 设置音频保存路径，保存音频格式支持pcm、wav，设置路径为sd卡请注意WRITE_EXTERNAL_STORAGE权限
        mTts.setParameter(SpeechConstant.AUDIO_FORMAT, "pcm");
        mTts.setParameter(SpeechConstant.TTS_AUDIO_PATH,
                getExternalFilesDir("msc").getAbsolutePath() + "/tts.pcm");

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mIat != null) {
            // 退出时释放连接
            mIat.cancel();
            mIat.destroy();
        }
    }
}