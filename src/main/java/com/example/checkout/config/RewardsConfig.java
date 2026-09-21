package com.example.checkout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "rewards")
public class RewardsConfig {
    private int n = 5;
    private int xPercent = 10;

    public int getN() { return n; }
    public void setN(int n) { this.n = n; }
    public int getXPercent() { return xPercent; }
    public void setXPercent(int xPercent) { this.xPercent = xPercent; }
}
