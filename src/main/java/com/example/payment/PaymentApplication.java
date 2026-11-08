package com.example.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Điểm khởi động của dịch vụ thanh toán VNPay.
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class PaymentApplication {
    /**
     * Khởi động ứng dụng Spring Boot và máy chủ HTTP.
     *
     * @param args các tham số dòng lệnh khi chạy ứng dụng
     */
    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
