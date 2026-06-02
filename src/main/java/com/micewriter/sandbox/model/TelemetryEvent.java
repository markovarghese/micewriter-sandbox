package com.micewriter.sandbox.model;

import com.micewriter.sdk.annotation.IcebergEntity;
import com.micewriter.sdk.annotation.IcebergId;

import java.time.Instant;
import java.util.List;

@IcebergEntity(table = "load_test_events", namespace = {"micewriter"})
public class TelemetryEvent {

    @IcebergId
    private String event_uuid;

    private Instant published_timestamp;
    private String ml_service_name;
    private String ml_service_version;

    // 8 Double Array Fields
    private List<Double> double_field_1;
    private List<Double> double_field_2;
    private List<Double> double_field_3;
    private List<Double> double_field_4;
    private List<Double> double_field_5;
    private List<Double> double_field_6;
    private List<Double> double_field_7;
    private List<Double> double_field_8;

    // 4 Integer Array Fields
    private List<Integer> int_field_1;
    private List<Integer> int_field_2;
    private List<Integer> int_field_3;
    private List<Integer> int_field_4;

    // 10 String Array Fields
    private List<String> string_field_1;
    private List<String> string_field_2;
    private List<String> string_field_3;
    private List<String> string_field_4;
    private List<String> string_field_5;
    private List<String> string_field_6;
    private List<String> string_field_7;
    private List<String> string_field_8;
    private List<String> string_field_9;
    private List<String> string_field_10;

    public TelemetryEvent() {}

    public String getEvent_uuid() { return event_uuid; }
    public void setEvent_uuid(String event_uuid) { this.event_uuid = event_uuid; }

    public Instant getPublished_timestamp() { return published_timestamp; }
    public void setPublished_timestamp(Instant published_timestamp) { this.published_timestamp = published_timestamp; }

    public String getMl_service_name() { return ml_service_name; }
    public void setMl_service_name(String ml_service_name) { this.ml_service_name = ml_service_name; }

    public String getMl_service_version() { return ml_service_version; }
    public void setMl_service_version(String ml_service_version) { this.ml_service_version = ml_service_version; }

    public List<Double> getDouble_field_1() { return double_field_1; }
    public void setDouble_field_1(List<Double> double_field_1) { this.double_field_1 = double_field_1; }

    public List<Double> getDouble_field_2() { return double_field_2; }
    public void setDouble_field_2(List<Double> double_field_2) { this.double_field_2 = double_field_2; }

    public List<Double> getDouble_field_3() { return double_field_3; }
    public void setDouble_field_3(List<Double> double_field_3) { this.double_field_3 = double_field_3; }

    public List<Double> getDouble_field_4() { return double_field_4; }
    public void setDouble_field_4(List<Double> double_field_4) { this.double_field_4 = double_field_4; }

    public List<Double> getDouble_field_5() { return double_field_5; }
    public void setDouble_field_5(List<Double> double_field_5) { this.double_field_5 = double_field_5; }

    public List<Double> getDouble_field_6() { return double_field_6; }
    public void setDouble_field_6(List<Double> double_field_6) { this.double_field_6 = double_field_6; }

    public List<Double> getDouble_field_7() { return double_field_7; }
    public void setDouble_field_7(List<Double> double_field_7) { this.double_field_7 = double_field_7; }

    public List<Double> getDouble_field_8() { return double_field_8; }
    public void setDouble_field_8(List<Double> double_field_8) { this.double_field_8 = double_field_8; }

    public List<Integer> getInt_field_1() { return int_field_1; }
    public void setInt_field_1(List<Integer> int_field_1) { this.int_field_1 = int_field_1; }

    public List<Integer> getInt_field_2() { return int_field_2; }
    public void setInt_field_2(List<Integer> int_field_2) { this.int_field_2 = int_field_2; }

    public List<Integer> getInt_field_3() { return int_field_3; }
    public void setInt_field_3(List<Integer> int_field_3) { this.int_field_3 = int_field_3; }

    public List<Integer> getInt_field_4() { return int_field_4; }
    public void setInt_field_4(List<Integer> int_field_4) { this.int_field_4 = int_field_4; }

    public List<String> getString_field_1() { return string_field_1; }
    public void setString_field_1(List<String> string_field_1) { this.string_field_1 = string_field_1; }

    public List<String> getString_field_2() { return string_field_2; }
    public void setString_field_2(List<String> string_field_2) { this.string_field_2 = string_field_2; }

    public List<String> getString_field_3() { return string_field_3; }
    public void setString_field_3(List<String> string_field_3) { this.string_field_3 = string_field_3; }

    public List<String> getString_field_4() { return string_field_4; }
    public void setString_field_4(List<String> string_field_4) { this.string_field_4 = string_field_4; }

    public List<String> getString_field_5() { return string_field_5; }
    public void setString_field_5(List<String> string_field_5) { this.string_field_5 = string_field_5; }

    public List<String> getString_field_6() { return string_field_6; }
    public void setString_field_6(List<String> string_field_6) { this.string_field_6 = string_field_6; }

    public List<String> getString_field_7() { return string_field_7; }
    public void setString_field_7(List<String> string_field_7) { this.string_field_7 = string_field_7; }

    public List<String> getString_field_8() { return string_field_8; }
    public void setString_field_8(List<String> string_field_8) { this.string_field_8 = string_field_8; }

    public List<String> getString_field_9() { return string_field_9; }
    public void setString_field_9(List<String> string_field_9) { this.string_field_9 = string_field_9; }

    public List<String> getString_field_10() { return string_field_10; }
    public void setString_field_10(List<String> string_field_10) { this.string_field_10 = string_field_10; }
}
