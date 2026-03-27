package com.screener.service.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.screener.service.enums.Market;
import com.screener.service.model.ScanResult;

public interface ScanResultRepository extends JpaRepository<ScanResult, Long> {
	List<ScanResult> findByScanDateOrderByScoreDesc(LocalDate scanDate);

	List<ScanResult> findByMarketAndScanDateOrderByScoreDesc(Market market, LocalDate scanDate);

	List<ScanResult> findByStockCodeOrderByScannedAtDesc(String stockCode);

	List<ScanResult> findByMarketAndStockCodeOrderByScannedAtDesc(Market market, String stockCode);

	List<ScanResult> findByMarketAndDecisionAndScanDateOrderByScoreDesc(Market market, String decision,
			LocalDate scanDate);

	Optional<ScanResult> findTopByMarketAndStockCodeOrderByScannedAtDesc(Market market, String code);
}
