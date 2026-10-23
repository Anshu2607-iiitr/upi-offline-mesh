package com.demo.upimesh.bank;

import com.demo.upimesh.model.LedgerEntry;
import com.demo.upimesh.model.Outcome;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface LedgerRepository extends JpaRepository<LedgerEntry, Long> {

    List<LedgerEntry> findAllByOrderByIdDesc(Pageable page);

    long countByPacketIdAndOutcome(String packetId, Outcome outcome);
}
