// Copyright 2017, Paul DeMarco.
// All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.boskokg.flutter_blue_plus;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

public class FlutterBluePlusPlugin implements FlutterPlugin, MethodCallHandler, RequestPermissionsResultListener, ActivityAware {

  private static final String TAG = "FlutterBluePlugin";
  private final Object initializationLock = new Object();
  private final Object tearDownLock = new Object();
  private Context context;
  private MethodChannel channel;
  private static final String NAMESPACE = "flutter_blue_plus";

  private EventChannel stateChannel;
  private BluetoothManager mBluetoothManager;
  private BluetoothAdapter mBluetoothAdapter;

  private FlutterPluginBinding pluginBinding;
  private ActivityPluginBinding activityBinding;

  static final private UUID CCCD_ID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
  private final Map<String, BluetoothDeviceCache> mDevices = new HashMap<>();
  private LogLevel logLevel = LogLevel.EMERGENCY;

  private interface OperationOnPermission {
    void op(boolean granted, String permission);
  }

  private int lastEventId = 1452;
  private final Map<Integer, OperationOnPermission> operationsOnPermission = new HashMap<>();

  private final ArrayList<String> macDeviceScanned = new ArrayList<>();
  private boolean allowDuplicates = false;

  public FlutterBluePlusPlugin() {}

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    Log.d(TAG, "onAttachedToEngine");
    pluginBinding = flutterPluginBinding;
    setup(pluginBinding.getBinaryMessenger(),
            (Application) pluginBinding.getApplicationContext());
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    Log.d(TAG, "onDetachedFromEngine");
    pluginBinding = null;
    tearDown();
  }

  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    Log.d(TAG, "onAttachedToActivity");
    activityBinding = binding;
    activityBinding.addRequestPermissionsResultListener(this);
  }

  @Override
  public void onDetachedFromActivityForConfigChanges() {
    Log.d(TAG, "onDetachedFromActivityForConfigChanges");
    onDetachedFromActivity();
  }

  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    Log.d(TAG, "onReattachedToActivityForConfigChanges");
    onAttachedToActivity(binding);
  }

  @Override
  public void onDetachedFromActivity() {
    Log.d(TAG, "onDetachedFromActivity");
    activityBinding.removeRequestPermissionsResultListener(this);
    activityBinding = null;
  }

  private void setup(
          final BinaryMessenger messenger,
          final Application application) {
    synchronized (initializationLock) {
      Log.d(TAG, "setup");
      this.context = application;
      channel = new MethodChannel(messenger, NAMESPACE + "/methods");
      channel.setMethodCallHandler(this);
      stateChannel = new EventChannel(messenger, NAMESPACE + "/state");
      stateChannel.setStreamHandler(stateHandler);
      mBluetoothManager = (BluetoothManager) application.getSystemService(Context.BLUETOOTH_SERVICE);
      mBluetoothAdapter = mBluetoothManager.getAdapter();
    }
  }

  private void tearDown() {
    synchronized (tearDownLock) {
      Log.d(TAG, "teardown");
      context = null;
      channel.setMethodCallHandler(null);
      channel = null;
      stateChannel.setStreamHandler(null);
      stateChannel = null;
      mBluetoothAdapter = null;
      mBluetoothManager = null;
    }
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    OperationOnPermission operation = operationsOnPermission.get(requestCode);
    if (operation != null && grantResults.length > 0) {
      operation.op(grantResults[0] == PackageManager.PERMISSION_GRANTED, permissions[0]);
      return true;
    }
    return false;
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    if(mBluetoothAdapter == null && !"isAvailable".equals(call.method)) {
      result.error("bluetooth_unavailable", "the device does not have bluetooth", null);
      return;
    }

    switch (call.method) {
      case "setLogLevel":
      {
        int logLevelIndex = (int)call.arguments;
        logLevel = LogLevel.values()[logLevelIndex];
        result.success(null);
        break;
      }

      case "state":
      {
        Protos.BluetoothState.Builder p = Protos.BluetoothState.newBuilder();
        try {
          switch(mBluetoothAdapter.getState()) {
            case BluetoothAdapter.STATE_OFF:
              p.setState(Protos.BluetoothState.State.OFF);
              break;
            case BluetoothAdapter.STATE_ON:
              p.setState(Protos.BluetoothState.State.ON);
              break;
            case BluetoothAdapter.STATE_TURNING_OFF:
              p.setState(Protos.BluetoothState.State.TURNING_OFF);
              break;
            case BluetoothAdapter.STATE_TURNING_ON:
              p.setState(Protos.BluetoothState.State.TURNING_ON);
              break;
            default:
              p.setState(Protos.BluetoothState.State.UNKNOWN);
              break;
          }
        } catch (SecurityException e) {
          p.setState(Protos.BluetoothState.State.UNAUTHORIZED);
        }
        result.success(p.build().toByteArray());
        break;
      }

      case "isAvailable":
      {
        result.success(mBluetoothAdapter != null);
        break;
      }

      case "isOn":
      {
        result.success(mBluetoothAdapter.isEnabled());
        break;
      }

      case "turnOn":
      {
        if (!mBluetoothAdapter.isEnabled()) {
          result.success(mBluetoothAdapter.enable());
        }
        break;
      }

      case "turnOff":
      {
        if (mBluetoothAdapter.isEnabled()) {
          result.success(mBluetoothAdapter.disable());
        }
        break;
      }

      case "pair":
      {
        String deviceId = (String)call.arguments;
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
        device.createBond();
        result.success(null);
        break;
      }

      case "removeBond":
      {
        String deviceId = (String)call.arguments;
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
        try {
          Boolean flag = android.src.main.java.com.boskokg.flutter_blue_plus.ClsUtils.removeBond(device.getClass(),device);
          result.success(flag);
        } catch (Exception e) {
          Log.d(TAG,"remove bond device exception");
          result.success(false);
        }
        break;
      }

      case "startScan":
      {
        ensurePermissionBeforeAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION, (grantedScan, permissionScan) -> {
          if (grantedScan) {
            ensurePermissionBeforeAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_CONNECT : null, (grantedConnect, permissionConnect) -> {
              if (grantedConnect)
                startScan(call, result);
              else
                result.error(
                        "no_permissions", String.format("flutter_blue plugin requires %s for scanning", permissionConnect), null);
            });
          }
          else
            result.error(
                    "no_permissions", String.format("flutter_blue plugin requires %s for scanning", permissionScan), null);
        });
        break;
      }

      case "stopScan":
      {
        stopScan();
        result.success(null);
        break;
      }

      case "getConnectedDevices":
      {
        ensurePermissionBeforeAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_CONNECT : null, (granted, permission) -> {
          if (!granted) {
            result.error(
                    "no_permissions", String.format("flutter_blue plugin requires %s for obtaining connected devices", permission), null);
            return;
          }
          List<BluetoothDevice> devices = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
          Protos.ConnectedDevicesResponse.Builder p = Protos.ConnectedDevicesResponse.newBuilder();
          for (BluetoothDevice d : devices) {
            p.addDevices(ProtoMaker.from(d));
          }
          result.success(p.build().toByteArray());
          log(LogLevel.EMERGENCY, "mDevices size: " + mDevices.size());
        });
        break;
      }

      case "getBondedDevices":
      {
        final Set<BluetoothDevice> bondedDevices = mBluetoothAdapter.getBondedDevices();
        Protos.ConnectedDevicesResponse.Builder p = Protos.ConnectedDevicesResponse.newBuilder();
        for (BluetoothDevice d : bondedDevices) {
          p.addDevices(ProtoMaker.from(d));
        }
        result.success(p.build().toByteArray());
        log(LogLevel.EMERGENCY, "mDevices size: " + mDevices.size());
        break;
      }

      case "connect":
      {
        ensurePermissionBeforeAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? Manifest.permission.BLUETOOTH_CONNECT : null, (granted, permission) -> {
          if (!granted) {
            result.error(
                    "no_permissions", String.format("flutter_blue plugin requires %s for new connection", permission), null);
            return;
          }
          byte[] data = call.arguments();
          Protos.ConnectRequest options;
          try {
            options = Protos.ConnectRequest.newBuilder().mergeFrom(data).build();
          } catch (InvalidProtocolBufferException e) {
            result.error("RuntimeException", e.getMessage(), e);
            return;
          }
          String deviceId = options.getRemoteId();
          BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
          boolean isConnected = mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT).contains(device);

          // If device is already connected, return error
          if(mDevices.containsKey(deviceId) && isConnected) {
            result.error("already_connected", "connection with device already exists", null);
            return;
          }

          // If device was connected to previously but is now disconnected, attempt a reconnect
          BluetoothDeviceCache bluetoothDeviceCache = mDevices.get(deviceId);
          if(bluetoothDeviceCache != null && !isConnected) {
            if(bluetoothDeviceCache.gatt.connect()){
              result.success(null);
            } else {
              result.error("reconnect_error", "error when reconnecting to device", null);
            }
            return;
          }

          // New request, connect and add gattServer to Map
          BluetoothGatt gattServer;
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            gattServer = device.connectGatt(context, options.getAndroidAutoConnect(), mGattCallback, BluetoothDevice.TRANSPORT_LE);
          } else {
            gattServer = device.connectGatt(context, options.getAndroidAutoConnect(), mGattCallback);
          }
          mDevices.put(deviceId, new BluetoothDeviceCache(gattServer));
          result.success(null);
        });
        break;
      }

      case "disconnect":
      {
        String deviceId = (String)call.arguments;
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
        BluetoothDeviceCache cache = mDevices.remove(deviceId);
        if(cache != null) {
          BluetoothGatt gattServer = cache.gatt;
          gattServer.disconnect();
          int state = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
          if(state == BluetoothProfile.STATE_DISCONNECTED) {
            gattServer.close();
          }
        }
        result.success(null);
        break;
      }

      case "deviceState":
      {
        String deviceId = (String)call.arguments;
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceId);
        int state = mBluetoothManager.getConnectionState(device, BluetoothProfile.GATT);
        try {
          result.success(ProtoMaker.from(device, state).toByteArray());
        } catch(Exception e) {
          result.error("device_state_error", e.getMessage(), e);
        }
        break;
      }

      case "discoverServices":
      {
        String deviceId = (String)call.arguments;
        try {
          BluetoothGatt gatt = locateGatt(deviceId);
          if(gatt.discoverServices()) {
            result.success(null);
          } else {
            result.error("discover_services_error", "unknown reason", null);
          }
        } catch(Exception e) {
          result.error("discover_services_error", e.getMessage(), e);
        }
        break;
      }

      case "services":
      {
        String deviceId = (String)call.arguments;
        try {
          BluetoothGatt gatt = locateGatt(deviceId);
          Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
          p.setRemoteId(deviceId);
          for(BluetoothGattService s : gatt.getServices()){
            p.addServices(ProtoMaker.from(gatt.getDevice(), s, gatt));
          }
          result.success(p.build().toByteArray());
        } catch(Exception e) {
          result.error("get_services_error", e.getMessage(), e);
        }
        break;
      }

      case "readCharacteristic":
      {
        byte[] data = call.arguments();
        Protos.ReadCharacteristicRequest request;
        try {
          request = Protos.ReadCharacteristicRequest.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
          result.error("RuntimeException", e.getMessage(), e);
          break;
        }

        BluetoothGatt gattServer;
        BluetoothGattCharacteristic characteristic;
        try {
          gattServer = locateGatt(request.getRemoteId());
          characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
        } catch(Exception e) {
          result.error("read_characteristic_error", e.getMessage(), null);
          return;
        }

        if(gattServer.readCharacteristic(characteristic)) {
          result.success(null);
        } else {
          result.error("read_characteristic_error", "unknown reason, may occur if readCharacteristic was called before last read finished.", null);
        }
        break;
      }

      case "readDescriptor":
      {
        byte[] data = call.arguments();
        Protos.ReadDescriptorRequest request;
        try {
          request = Protos.ReadDescriptorRequest.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
          result.error("RuntimeException", e.getMessage(), e);
          break;
        }

        BluetoothGatt gattServer;
        BluetoothGattCharacteristic characteristic;
        BluetoothGattDescriptor descriptor;
        try {
          gattServer = locateGatt(request.getRemoteId());
          characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
          descriptor = locateDescriptor(characteristic, request.getDescriptorUuid());
        } catch(Exception e) {
          result.error("read_descriptor_error", e.getMessage(), null);
          return;
        }

        if(gattServer.readDescriptor(descriptor)) {
          result.success(null);
        } else {
          result.error("read_descriptor_error", "unknown reason, may occur if readDescriptor was called before last read finished.", null);
        }
        break;
      }

      case "writeCharacteristic":
      {
        byte[] data = call.arguments();
        Protos.WriteCharacteristicRequest request;
        try {
          request = Protos.WriteCharacteristicRequest.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
          result.error("RuntimeException", e.getMessage(), e);
          break;
        }

        BluetoothGatt gattServer;
        BluetoothGattCharacteristic characteristic;
        try {
          gattServer = locateGatt(request.getRemoteId());
          characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
        } catch(Exception e) {
          result.error("write_characteristic_error", e.getMessage(), null);
          return;
        }

        // Set characteristic to new value
        if(!characteristic.setValue(request.getValue().toByteArray())){
          result.error("write_characteristic_error", "could not set the local value of characteristic", null);
        }

        // Apply the correct write type
        if(request.getWriteType() == Protos.WriteCharacteristicRequest.WriteType.WITHOUT_RESPONSE) {
          characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
        } else {
          characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        }

        if(!gattServer.writeCharacteristic(characteristic)){
          result.error("write_characteristic_error", "writeCharacteristic failed", null);
          return;
        }

        result.success(null);
        break;
      }

      case "writeDescriptor":
      {
        byte[] data = call.arguments();
        Protos.WriteDescriptorRequest request;
        try {
          request = Protos.WriteDescriptorRequest.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
          result.error("RuntimeException", e.getMessage(), e);
          break;
        }

        BluetoothGatt gattServer;
        BluetoothGattCharacteristic characteristic;
        BluetoothGattDescriptor descriptor;
        try {
          gattServer = locateGatt(request.getRemoteId());
          characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
          descriptor = locateDescriptor(characteristic, request.getDescriptorUuid());
        } catch(Exception e) {
          result.error("write_descriptor_error", e.getMessage(), null);
          return;
        }

        // Set descriptor to new value
        if(!descriptor.setValue(request.getValue().toByteArray())){
          result.error("write_descriptor_error", "could not set the local value for descriptor", null);
        }

        if(!gattServer.writeDescriptor(descriptor)){
          result.error("write_descriptor_error", "writeCharacteristic failed", null);
          return;
        }

        result.success(null);
        break;
      }

      case "setNotification":
      {
        byte[] data = call.arguments();
        Protos.SetNotificationRequest request;
        try {
          request = Protos.SetNotificationRequest.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
          result.error("RuntimeException", e.getMessage(), e);
          break;
        }

        BluetoothGatt gattServer;
        BluetoothGattCharacteristic characteristic;
        BluetoothGattDescriptor cccDescriptor;
        try {
          gattServer = locateGatt(request.getRemoteId());
          characteristic = locateCharacteristic(gattServer, request.getServiceUuid(), request.getSecondaryServiceUuid(), request.getCharacteristicUuid());
          cccDescriptor = characteristic.getDescriptor(CCCD_ID);
          if(cccDescriptor == null) {
            //Some devices - including the widely used Bluno do not actually set the CCCD_ID.
            //thus setNotifications works perfectly (tested on Bluno) without cccDescriptor
            log(LogLevel.INFO, "could not locate CCCD descriptor for characteristic: " + characteristic.getUuid().toString());
          }
        } catch(Exception e) {
          result.error("set_notification_error", e.getMessage(), null);
          return;
        }

        byte[] value = null;

        if(request.getEnable()) {
          boolean canNotify = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0;
          boolean canIndicate = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) > 0;
          if(!canIndicate && !canNotify) {
            result.error("set_notification_error", "the characteristic cannot notify or indicate", null);
            return;
          }
          if(canIndicate) {
            value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
          }
          if(canNotify) {
            value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
          }
        } else {
          value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        }

        if(!gattServer.setCharacteristicNotification(characteristic, request.getEnable())){
          result.error("set_notification_error", "could not set characteristic notifications to :" + request.getEnable(), null);
          return;
        }

        if(cccDescriptor != null) {
          if (!cccDescriptor.setValue(value)) {
            result.error("set_notification_error", "error when setting the descriptor value to: " + Arrays.toString(value), null);
            return;
          }

          if (!gattServer.writeDescriptor(cccDescriptor)) {
            result.error("set_notification_error", "error when writing the descriptor", null);
            return;
          }
        }

        result.success(null);
        break;
      }

      case "mtu":
      {
        String deviceId = (String)call.arguments;
        BluetoothDeviceCache cache = mDevices.get(deviceId);
        if(cache != null) {
          Protos.MtuSizeResponse.Builder p = Protos.MtuSizeResponse.newBuilder();
          p.setRemoteId(deviceId);
          p.setMtu(cache.mtu);
          result.success(p.build().toByteArray());
        } else {
          result.error("mtu", "no instance of BluetoothGatt, have you connected first?", null);
        }
        break;
      }

      case "requestMtu":
      {
        byte[] data = call.arguments();
        Protos.MtuSizeRequest request;
        try {
          request = Protos.MtuSizeRequest.newBuilder().mergeFrom(data).build();
        } catch (InvalidProtocolBufferException e) {
          result.error("RuntimeException", e.getMessage(), e);
          break;
        }

        BluetoothGatt gatt;
        try {
          gatt = locateGatt(request.getRemoteId());
          int mtu = request.getMtu();
          if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if(gatt.requestMtu(mtu)) {
              result.success(null);
            } else {
              result.error("requestMtu", "gatt.requestMtu returned false", null);
            }
          } else {
            result.error("requestMtu", "Only supported on devices >= API 21 (Lollipop). This device == " + Build.VERSION.SDK_INT, null);
          }
        } catch(Exception e) {
          result.error("requestMtu", e.getMessage(), e);
        }

        break;
      }

      case "readRssi":
      {
        String remoteId = (String)call.arguments;
        BluetoothGatt gatt;
        try {
          gatt = locateGatt(remoteId);
          if(gatt.readRemoteRssi()) {
            result.success(null);
          } else {
            result.error("readRssi", "gatt.readRemoteRssi returned false", null);
          }
        } catch(Exception e) {
          result.error("readRssi", e.getMessage(), e);
        }

        break;
      }

      default:
      {
        result.notImplemented();
        break;
      }
    }
  }

  private void ensurePermissionBeforeAction(String permission, OperationOnPermission operation) {
    if (permission != null &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
      operationsOnPermission.put(lastEventId, (granted, perm) -> {
        operationsOnPermission.remove(lastEventId);
        operation.op(granted, perm);
      });
      ActivityCompat.requestPermissions(
              activityBinding.getActivity(),
              new String[]{permission},
              lastEventId);
      lastEventId++;
    } else {
      operation.op(true, permission);
    }
  }

  private BluetoothGatt locateGatt(String remoteId) throws Exception {
    BluetoothDeviceCache cache = mDevices.get(remoteId);
    if(cache == null || cache.gatt == null) {
      throw new Exception("no instance of BluetoothGatt, have you connected first?");
    } else {
      return cache.gatt;
    }
  }

  private BluetoothGattCharacteristic locateCharacteristic(BluetoothGatt gattServer, String serviceId, String secondaryServiceId, String characteristicId) throws Exception {
    BluetoothGattService primaryService = gattServer.getService(UUID.fromString(serviceId));
    if(primaryService == null) {
      throw new Exception("service (" + serviceId + ") could not be located on the device");
    }
    BluetoothGattService secondaryService = null;
    if(secondaryServiceId.length() > 0) {
      for(BluetoothGattService s : primaryService.getIncludedServices()){
        if(s.getUuid().equals(UUID.fromString(secondaryServiceId))){
          secondaryService = s;
        }
      }
      if(secondaryService == null) {
        throw new Exception("secondary service (" + secondaryServiceId + ") could not be located on the device");
      }
    }
    BluetoothGattService service = (secondaryService != null) ? secondaryService : primaryService;
    BluetoothGattCharacteristic characteristic = service.getCharacteristic(UUID.fromString(characteristicId));
    if(characteristic == null) {
      throw new Exception("characteristic (" + characteristicId + ") could not be located in the service ("+service.getUuid().toString()+")");
    }
    return characteristic;
  }

  private BluetoothGattDescriptor locateDescriptor(BluetoothGattCharacteristic characteristic, String descriptorId) throws Exception {
    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(UUID.fromString(descriptorId));
    if(descriptor == null) {
      throw new Exception("descriptor (" + descriptorId + ") could not be located in the characteristic ("+characteristic.getUuid().toString()+")");
    }
    return descriptor;
  }

  private final StreamHandler stateHandler = new StreamHandler() {
    private EventSink sink;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
          final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                  BluetoothAdapter.ERROR);
          switch (state) {
            case BluetoothAdapter.STATE_OFF:
              sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.OFF).build().toByteArray());
              break;
            case BluetoothAdapter.STATE_TURNING_OFF:
              sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.TURNING_OFF).build().toByteArray());
              break;
            case BluetoothAdapter.STATE_ON:
              sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.ON).build().toByteArray());
              break;
            case BluetoothAdapter.STATE_TURNING_ON:
              sink.success(Protos.BluetoothState.newBuilder().setState(Protos.BluetoothState.State.TURNING_ON).build().toByteArray());
              break;
          }
        }
      }
    };

    @Override
    public void onListen(Object o, EventChannel.EventSink eventSink) {
      sink = eventSink;
      IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
      context.registerReceiver(mReceiver, filter);
    }

    @Override
    public void onCancel(Object o) {
      sink = null;
      context.unregisterReceiver(mReceiver);
    }
  };

  private void startScan(MethodCall call, Result result) {
    byte[] data = call.arguments();
    Protos.ScanSettings settings;
    try {
      settings = Protos.ScanSettings.newBuilder().mergeFrom(data).build();
      allowDuplicates = settings.getAllowDuplicates();
      macDeviceScanned.clear();
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        startScan21(settings);
      } else {
        startScan18(settings);
      }
      result.success(null);
    } catch (Exception e) {
      result.error("startScan", e.getMessage(), e);
    }
  }

  private void stopScan() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      stopScan21();
    } else {
      stopScan18();
    }
  }

  private ScanCallback scanCallback21;

  @TargetApi(21)
  private ScanCallback getScanCallback21() {
    if(scanCallback21 == null){
      scanCallback21 = new ScanCallback() {

        @Override
        public void onScanResult(int callbackType, ScanResult result) {
          super.onScanResult(callbackType, result);
          if(result != null){
            if (!allowDuplicates && result.getDevice() != null && result.getDevice().getAddress() != null) {
              if (macDeviceScanned.contains(result.getDevice().getAddress())) {
                return;
              }
              macDeviceScanned.add(result.getDevice().getAddress());
            }
            Protos.ScanResult scanResult = ProtoMaker.from(result.getDevice(), result);
            invokeMethodUIThread("ScanResult", scanResult.toByteArray());
          }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
          super.onBatchScanResults(results);

        }

        @Override
        public void onScanFailed(int errorCode) {
          super.onScanFailed(errorCode);
        }
      };
    }
    return scanCallback21;
  }

  @TargetApi(21)
  private void startScan21(Protos.ScanSettings proto) throws IllegalStateException {
    BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
    if(scanner == null) throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");
    int scanMode = proto.getAndroidScanMode();
    int count = proto.getServiceUuidsCount();
    List<ScanFilter> filters = new ArrayList<>(count);
    for(int i = 0; i < count; i++) {
      String uuid = proto.getServiceUuids(i);
      ScanFilter f = new ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(uuid)).build();
      filters.add(f);
    }
    ScanSettings settings = new ScanSettings.Builder().setScanMode(scanMode).build();
    scanner.startScan(filters, settings, getScanCallback21());
  }

  @TargetApi(21)
  private void stopScan21() {
    BluetoothLeScanner scanner = mBluetoothAdapter.getBluetoothLeScanner();
    if(scanner != null) scanner.stopScan(getScanCallback21());
  }

  private BluetoothAdapter.LeScanCallback scanCallback18;

  private BluetoothAdapter.LeScanCallback getScanCallback18() {
    if(scanCallback18 == null) {
      scanCallback18 = (bluetoothDevice, rssi, scanRecord) -> {
        if (!allowDuplicates && bluetoothDevice != null && bluetoothDevice.getAddress() != null) {
          if (macDeviceScanned.contains(bluetoothDevice.getAddress())) return;
          macDeviceScanned.add(bluetoothDevice.getAddress());
        }

        Protos.ScanResult scanResult = ProtoMaker.from(bluetoothDevice, scanRecord, rssi);
        invokeMethodUIThread("ScanResult", scanResult.toByteArray());
      };
    }
    return scanCallback18;
  }

  private void startScan18(Protos.ScanSettings proto) throws IllegalStateException {
    List<String> serviceUuids = proto.getServiceUuidsList();
    UUID[] uuids = new UUID[serviceUuids.size()];
    for(int i = 0; i < serviceUuids.size(); i++) {
      uuids[i] = UUID.fromString(serviceUuids.get(i));
    }
    boolean success = mBluetoothAdapter.startLeScan(uuids, getScanCallback18());
    if(!success) throw new IllegalStateException("getBluetoothLeScanner() is null. Is the Adapter on?");
  }

  private void stopScan18() {
    mBluetoothAdapter.stopLeScan(getScanCallback18());
  }

  private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
      log(LogLevel.DEBUG, "[onConnectionStateChange] status: " + status + " newState: " + newState);
      if(newState == BluetoothProfile.STATE_DISCONNECTED) {
        if(!mDevices.containsKey(gatt.getDevice().getAddress())) {
          gatt.close();
        }
      }
      invokeMethodUIThread("DeviceState", ProtoMaker.from(gatt.getDevice(), newState).toByteArray());
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
      log(LogLevel.DEBUG, "[onServicesDiscovered] count: " + gatt.getServices().size() + " status: " + status);
      Protos.DiscoverServicesResult.Builder p = Protos.DiscoverServicesResult.newBuilder();
      p.setRemoteId(gatt.getDevice().getAddress());
      for(BluetoothGattService s : gatt.getServices()) {
        p.addServices(ProtoMaker.from(gatt.getDevice(), s, gatt));
      }
      invokeMethodUIThread("DiscoverServicesResult", p.build().toByteArray());
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      log(LogLevel.DEBUG, "[onCharacteristicRead] uuid: " + characteristic.getUuid().toString() + " status: " + status);
      Protos.ReadCharacteristicResponse.Builder p = Protos.ReadCharacteristicResponse.newBuilder();
      p.setRemoteId(gatt.getDevice().getAddress());
      p.setCharacteristic(ProtoMaker.from(gatt.getDevice(), characteristic, gatt));
      invokeMethodUIThread("ReadCharacteristicResponse", p.build().toByteArray());
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
      log(LogLevel.DEBUG, "[onCharacteristicWrite] uuid: " + characteristic.getUuid().toString() + " status: " + status);
      Protos.WriteCharacteristicRequest.Builder request = Protos.WriteCharacteristicRequest.newBuilder();
      request.setRemoteId(gatt.getDevice().getAddress());
      request.setCharacteristicUuid(characteristic.getUuid().toString());
      request.setServiceUuid(characteristic.getService().getUuid().toString());
      Protos.WriteCharacteristicResponse.Builder p = Protos.WriteCharacteristicResponse.newBuilder();
      p.setRequest(request);
      p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
      invokeMethodUIThread("WriteCharacteristicResponse", p.build().toByteArray());
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
      log(LogLevel.DEBUG, "[onCharacteristicChanged] uuid: " + characteristic.getUuid().toString());
      Protos.OnCharacteristicChanged.Builder p = Protos.OnCharacteristicChanged.newBuilder();
      p.setRemoteId(gatt.getDevice().getAddress());
      p.setCharacteristic(ProtoMaker.from(gatt.getDevice(), characteristic, gatt));
      invokeMethodUIThread("OnCharacteristicChanged", p.build().toByteArray());
    }

    @Override
    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
      log(LogLevel.DEBUG, "[onDescriptorRead] uuid: " + descriptor.getUuid().toString() + " status: " + status);
      // Rebuild the ReadAttributeRequest and send back along with response
      Protos.ReadDescriptorRequest.Builder q = Protos.ReadDescriptorRequest.newBuilder();
      q.setRemoteId(gatt.getDevice().getAddress());
      q.setCharacteristicUuid(descriptor.getCharacteristic().getUuid().toString());
      q.setDescriptorUuid(descriptor.getUuid().toString());
      if(descriptor.getCharacteristic().getService().getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY) {
        q.setServiceUuid(descriptor.getCharacteristic().getService().getUuid().toString());
      } else {
        // Reverse search to find service
        for(BluetoothGattService s : gatt.getServices()) {
          for(BluetoothGattService ss : s.getIncludedServices()) {
            if(ss.getUuid().equals(descriptor.getCharacteristic().getService().getUuid())){
              q.setServiceUuid(s.getUuid().toString());
              q.setSecondaryServiceUuid(ss.getUuid().toString());
              break;
            }
          }
        }
      }
      Protos.ReadDescriptorResponse.Builder p = Protos.ReadDescriptorResponse.newBuilder();
      p.setRequest(q);
      p.setValue(ByteString.copyFrom(descriptor.getValue()));
      invokeMethodUIThread("ReadDescriptorResponse", p.build().toByteArray());
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
      log(LogLevel.DEBUG, "[onDescriptorWrite] uuid: " + descriptor.getUuid().toString() + " status: " + status);
      Protos.WriteDescriptorRequest.Builder request = Protos.WriteDescriptorRequest.newBuilder();
      request.setRemoteId(gatt.getDevice().getAddress());
      request.setDescriptorUuid(descriptor.getUuid().toString());
      request.setCharacteristicUuid(descriptor.getCharacteristic().getUuid().toString());
      request.setServiceUuid(descriptor.getCharacteristic().getService().getUuid().toString());
      Protos.WriteDescriptorResponse.Builder p = Protos.WriteDescriptorResponse.newBuilder();
      p.setRequest(request);
      p.setSuccess(status == BluetoothGatt.GATT_SUCCESS);
      invokeMethodUIThread("WriteDescriptorResponse", p.build().toByteArray());

      if(descriptor.getUuid().compareTo(CCCD_ID) == 0) {
        // SetNotificationResponse
        Protos.SetNotificationResponse.Builder q = Protos.SetNotificationResponse.newBuilder();
        q.setRemoteId(gatt.getDevice().getAddress());
        q.setCharacteristic(ProtoMaker.from(gatt.getDevice(), descriptor.getCharacteristic(), gatt));
        invokeMethodUIThread("SetNotificationResponse", q.build().toByteArray());
      }
    }

    @Override
    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
      log(LogLevel.DEBUG, "[onReliableWriteCompleted] status: " + status);
    }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
      log(LogLevel.DEBUG, "[onReadRemoteRssi] rssi: " + rssi + " status: " + status);
      if(status == BluetoothGatt.GATT_SUCCESS) {
        Protos.ReadRssiResult.Builder p = Protos.ReadRssiResult.newBuilder();
        p.setRemoteId(gatt.getDevice().getAddress());
        p.setRssi(rssi);
        invokeMethodUIThread("ReadRssiResult", p.build().toByteArray());
      }
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
      log(LogLevel.DEBUG, "[onMtuChanged] mtu: " + mtu + " status: " + status);
      if(status == BluetoothGatt.GATT_SUCCESS) {
        if(mDevices.containsKey(gatt.getDevice().getAddress())) {
          BluetoothDeviceCache cache = mDevices.get(gatt.getDevice().getAddress());
          if (cache != null) {
            cache.mtu = mtu;
          }
          Protos.MtuSizeResponse.Builder p = Protos.MtuSizeResponse.newBuilder();
          p.setRemoteId(gatt.getDevice().getAddress());
          p.setMtu(mtu);
          invokeMethodUIThread("MtuSize", p.build().toByteArray());
        }
      }
    }
  };

  private void log(LogLevel level, String message) {
    if(level.ordinal() <= logLevel.ordinal()) {
      Log.d(TAG, message);
    }
  }

  private void invokeMethodUIThread(final String name, final byte[] byteArray)
  {
    new Handler(Looper.getMainLooper()).post(() -> {
      synchronized (tearDownLock) {
        //Could already be teared down at this moment
        if (channel != null) {
          channel.invokeMethod(name, byteArray);
        } else {
          Log.w(TAG, "Tried to call " + name + " on closed channel");
        }
      }
    });
  }

  enum LogLevel
  {
    EMERGENCY, ALERT, CRITICAL, ERROR, WARNING, NOTICE, INFO, DEBUG
  }

  // BluetoothDeviceCache contains any other cached information not stored in Android Bluetooth API
  // but still needed Dart side.
  static class BluetoothDeviceCache {
    final BluetoothGatt gatt;
    int mtu;

    BluetoothDeviceCache(BluetoothGatt gatt) {
      this.gatt = gatt;
      mtu = 20;
    }
  }
}
