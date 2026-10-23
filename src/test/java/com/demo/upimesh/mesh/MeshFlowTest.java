package com.demo.upimesh.mesh;

import com.demo.upimesh.bank.BankService;
import com.demo.upimesh.client.PacketFactory;
import com.demo.upimesh.model.IngestResult;
import com.demo.upimesh.model.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:mesh-test;DB_CLOSE_DELAY=-1")
class MeshFlowTest {

    @Autowired
    MeshSimulator mesh;
    @Autowired
    BankService bank;
    @Autowired
    PacketFactory phone;

    @BeforeEach
    void reset() {
        bank.reset();
        mesh.reset();
    }

    @Test
    void offlinePaymentReachesBankThroughMeshAndSettlesOnce() throws Exception {
        mesh.inject("pratyush", phone.create("pratyush@sbi", "mohan@hdfc", new BigDecimal("100"), "1234", Instant.now()));

        mesh.gossipRound(); // pratyush → manoj, sunita
        mesh.gossipRound(); // → ajay
        mesh.gossipRound(); // → kiran

        List<IngestResult> results = mesh.flushBridges(); // sunita, ajay and kiran all race
        assertThat(results).hasSize(3);
        assertThat(results).extracting(IngestResult::outcome).containsOnlyOnce(Outcome.SETTLED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4900");
        assertThat(bank.balanceOf("mohan@hdfc")).isEqualByComparingTo("1100");
    }

    @Test
    void gossipStopsWhenHopTtlRunsOut() {
        var packet = phone.create("pratyush@sbi", "mohan@hdfc", new BigDecimal("1"), "1234", Instant.now());
        mesh.inject("pratyush", packet.withTtl(1)); // one hop allowed
        for (int i = 0; i < 5; i++) {
            mesh.gossipRound();
        }

        assertThat(mesh.devices()).filteredOn(d -> !d.heldPacketIds().isEmpty())
                .extracting(MeshSimulator.DeviceView::id)
                .containsExactlyInAnyOrder("pratyush", "manoj", "sunita");
    }

    @Test
    void uploadedPacketDoesNotBounceBackIntoBridge() throws Exception {
        mesh.inject("pratyush", phone.create("pratyush@sbi", "mohan@hdfc", new BigDecimal("1"), "1234", Instant.now()));
        mesh.gossipRound();
        mesh.flushBridges();
        mesh.gossipRound();

        assertThat(mesh.devices()).filteredOn(d -> d.id().equals("sunita"))
                .singleElement().satisfies(d -> assertThat(d.heldPacketIds()).isEmpty());
    }
}
