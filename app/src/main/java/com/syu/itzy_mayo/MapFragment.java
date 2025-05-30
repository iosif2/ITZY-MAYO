package com.syu.itzy_mayo;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;
import com.google.firebase.firestore.ListenerRegistration;
import com.naver.maps.geometry.LatLng;
import com.naver.maps.map.LocationTrackingMode;
import com.naver.maps.map.NaverMap;
import com.naver.maps.map.OnMapReadyCallback;
import com.naver.maps.map.UiSettings;
import com.naver.maps.map.overlay.Marker;
import com.naver.maps.map.overlay.OverlayImage;
import com.naver.maps.map.util.FusedLocationSource;
import com.syu.itzy_mayo.Schedule.Schedule;

import java.util.Iterator;
import java.util.Map;

public class MapFragment extends Fragment implements OnMapReadyCallback, AuthStateObserver {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1000;
    private FusedLocationSource locationSource;
    private NaverMap naverMap;
    private FusedLocationProviderClient fusedLocationClient;
    private final ArrayMap<String, Marker> mapMarkers = new ArrayMap<>();
    private ListenerRegistration scheduleListener;
    private UserSessionManager sessionManager;
    private FirebaseFirestore db;

    private void listenScheduleMarkers() {
        if (sessionManager.isLoggedIn()) {
            String uid = sessionManager.getUserId();
            if (uid == null)
                return;
            if (scheduleListener != null) {
                scheduleListener.remove();
                scheduleListener = null;
            }
            scheduleListener = db.collection("schedule").whereEqualTo("userId", uid)
                    .addSnapshotListener((snapshots, e) -> {
                        if (e != null) {
                            Log.w("Firestore", "Listen failed.", e);
                            return;
                        }
                        if (snapshots == null) {
                            return;
                        }
                        Iterator<Map.Entry<String, Marker>> iterator = mapMarkers.entrySet().iterator();
                        while (iterator.hasNext()) {
                            Map.Entry<String, Marker> entry = iterator.next();
                            Marker marker = entry.getValue();
                            if (marker != null) {
                                marker.setMap(null);
                                iterator.remove();
                            }
                        }
                        for (DocumentSnapshot doc : snapshots) {
                            Schedule schedule = doc.toObject(Schedule.class);
                            if (schedule != null && schedule.getTitle() != null && schedule.getGeoPoint() != null) {
                                // 이 코드가 실행되면 db에서 가져온 일정 데이터에 설정된 위치가 지도에 표사됨(DB변경 감지 시)
                                addMarker(schedule.getTitle(), schedule.getGeoPoint());
                            }
                        }
                    });
        }
    }

    public MapFragment() {
        super(R.layout.fragment_map);
    }

    // 권한 요청 런처
    private final ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    initMap();
                    naverMap.setLocationTrackingMode(LocationTrackingMode.Follow);
                } else {
                    showMissingPermissionError();
                    naverMap.setLocationTrackingMode(LocationTrackingMode.None);
                }
            });

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 권한 요청을 위한 LocationSource 초기화
        locationSource = new FusedLocationSource(this, LOCATION_PERMISSION_REQUEST_CODE);

        // FusedLocationProviderClient 초기화 (현재 위치 가져오기 위함)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        // 지도 초기화
        initMap();
        sessionManager = ItzyMayoApplication.getInstance().getSessionManager();
        sessionManager.addObserver(this);
        db = FirebaseFirestore.getInstance();
        listenScheduleMarkers();
    }

    private void initMap() {
        FragmentManager fm = getChildFragmentManager();
        com.naver.maps.map.MapFragment mapFragment = (com.naver.maps.map.MapFragment) fm.findFragmentById(R.id.map_fragment);
        if (mapFragment == null) {
            mapFragment = com.naver.maps.map.MapFragment.newInstance();
            fm.beginTransaction().add(R.id.map_fragment, mapFragment).commit();
        }
        mapFragment.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull NaverMap naverMap) {
        this.naverMap = naverMap;
        // UI 설정
        UiSettings uiSettings = naverMap.getUiSettings();
        uiSettings.setCompassEnabled(true);
        uiSettings.setScaleBarEnabled(true);
        uiSettings.setZoomControlEnabled(false);
        uiSettings.setLocationButtonEnabled(true);
        uiSettings.setZoomGesturesEnabled(true);


        // 위치 추적 모드 및 소스 설정
        this.naverMap.setLocationSource(locationSource);

        // 위치 권한 확인
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            naverMap.setLocationTrackingMode(LocationTrackingMode.Follow);
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        showCurrentLocation();

        // naverMap이 준비된 후, 로그인 상태라면 마커를 추가
        if (sessionManager.isLoggedIn()) {
            String uid = sessionManager.getUserId();
            if (uid != null) {
                // 이 코드가 실행되면 db에서 가져온 일정 데이터에 설정된 위치가 지도에 표사됨(지도 로딩 완료 후)
                addScheduleMarkers(uid);
            }
        }
    }

    private void addMarker(String key, GeoPoint geoPoint) {
        LatLng syu = new LatLng(geoPoint.getLatitude(), geoPoint.getLongitude());
        Marker marker = new Marker();
        marker.setPosition(syu);
        marker.setIcon(OverlayImage.fromResource(R.drawable.ic_marker));
        marker.setWidth(80);
        marker.setHeight(80);
        marker.setCaptionText(key);
        marker.setCaptionColor(Color.BLACK);
        marker.setCaptionHaloColor(Color.WHITE);
        marker.setCaptionTextSize(16);
        marker.setMap(naverMap);
        this.mapMarkers.put(key, marker);
    }

    private void showCurrentLocation() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION);
            return;
        }

        fusedLocationClient.getLastLocation().addOnSuccessListener(requireActivity(),
                location -> {
                    if (location != null && naverMap != null) {
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();
                        Log.i("Location", "latitude: " + latitude + " longitude: " + longitude);
                    }
                });
    }

    private void addScheduleMarkers(String uid) {
        db.collection("schedule").whereEqualTo("userId", uid)
                .get()
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Iterator<Map.Entry<String, Marker>> iterator = mapMarkers.entrySet().iterator();
                        while (iterator.hasNext()) {
                            Map.Entry<String, Marker> entry = iterator.next();
                            Marker marker = entry.getValue();
                            if (marker != null) {
                                marker.setMap(null);
                                iterator.remove();
                            }
                        }
                        // DB에서 받아온 schedule로 마커 다시 생성
                        for (DocumentSnapshot doc : task.getResult()) {
                            Schedule schedule = doc.toObject(Schedule.class);
                            if (schedule != null && schedule.getTitle() != null && schedule.getGeoPoint() != null) {
                                // 실제 db에서 가져온 일정 데이터에 설정된 위치가 지도에 표시하는 코드
                                addMarker(schedule.getTitle(), schedule.getGeoPoint());
                            }
                        }
                    }
                });
    }

    private void showMissingPermissionError() {
        Toast.makeText(getContext(), "Location permission is required to show your position.",
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (scheduleListener != null) {
            scheduleListener.remove();
            scheduleListener = null;
        }
    }

    @Override
    public void onAuthStateChanged(FirebaseUser user) {
        if (user != null && sessionManager.isLoggedIn()) {
            String uid = sessionManager.getUserId();
            if (uid != null) {
                // 기존 마커 중 SYU를 제외하고 모두 삭제
                Iterator<Map.Entry<String, Marker>> iterator = mapMarkers.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Marker> entry = iterator.next();
                    String key = entry.getKey();
                    if (key.equals("SYU"))
                        continue;
                    Marker marker = entry.getValue();
                    if (marker != null) {
                        marker.setMap(null);
                        iterator.remove();
                    }
                }
                // 이 코드가 실행되면 db에서 가져온 일정 데이터에 설정된 위치가 지도에 표사됨(인증정보 변경 시)
                addScheduleMarkers(uid);
            }
        } else {
            Iterator<Map.Entry<String, Marker>> iterator = mapMarkers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Marker> entry = iterator.next();
                Marker marker = entry.getValue();
                if (marker != null) {
                    marker.setMap(null);
                    iterator.remove();
                }
            }
        }
    }
}
