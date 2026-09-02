package com.auditflow.gateway.controllers;

import com.auditflow.common.interfaces.ReportGenerator;
import com.auditflow.common.reports.GDPRReportGenerator;
import com.auditflow.common.reports.HIPAAReportGenerator;
import com.auditflow.common.reports.SOC2ReportGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** The framework report generators (pure functions over events, shared in common-lib). */
@Configuration
public class ReportsConfig {

    @Bean
    public List<ReportGenerator> reportGenerators() {
        return List.of(new SOC2ReportGenerator(), new GDPRReportGenerator(), new HIPAAReportGenerator());
    }
}
