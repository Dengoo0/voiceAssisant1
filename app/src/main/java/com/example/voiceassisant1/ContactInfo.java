package com.example.voiceassisant1;

//联系人信息类
public class ContactInfo {
    private String name;
    private String number;

    public ContactInfo(String name, String number) {
        this.name = name;
        this.number = number;
    }

    public String getName() {
        return name;
    }

    public String getNumber() {
        return number;
    }
}

