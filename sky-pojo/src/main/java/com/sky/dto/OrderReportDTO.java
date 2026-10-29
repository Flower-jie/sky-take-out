package com.sky.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OrderReportDTO implements Serializable {
    private LocalDateTime begin;
    private LocalDateTime end;
    private Integer status;
}
