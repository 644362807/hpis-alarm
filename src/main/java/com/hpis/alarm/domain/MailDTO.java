package com.hpis.alarm.domain;

import lombok.Data;

import java.util.Date;

@Data
public class MailDTO {
    private String from;
    private String replyTo;
    private String[] to;
    private String[] cc;
    private String[] bcc;
    private Date sentDate;
    private String subject;
    private String text;
    private String[] filenames;
}
