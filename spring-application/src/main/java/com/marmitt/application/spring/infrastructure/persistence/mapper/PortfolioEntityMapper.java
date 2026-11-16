package com.marmitt.application.spring.infrastructure.persistence.mapper;

import com.marmitt.application.spring.infrastructure.persistence.entity.PortfolioEntity;
import com.marmitt.core.domain.portfolio.Portfolio;

public class PortfolioEntityMapper {

    // ==================== Domain → Entity ====================

    public static PortfolioEntity toPortfolioEntity(Portfolio domain) {
        return PortfolioEntity.builder()
                .id(domain.getId())
                .name(domain.getName())
                .isActive(domain.isActive())
                .safeModeStatus(domain.getSafeModeStatus())
                .capitalPoolingMode(domain.getCapitalPoolingMode())
                .safeModeOrigin(domain.getSafeModeOrigin())
                .safeModeTrigger(domain.getSafeModeTrigger())
                .safeModeReason(domain.getSafeModeReason())
                .safeModeChangedAt(domain.getSafeModeChangedAt())
                .createdAt(domain.getCreatedAt())
                .version(domain.getVersion())
                .build();
    }

    // ==================== Entity → Domain ====================

    public static Portfolio toDomain(PortfolioEntity entity) {
        return new Portfolio(
                entity.getId(),
                entity.getName(),
                entity.isActive(),
                entity.getCreatedAt(),
                entity.getSafeModeStatus(),
                entity.getCapitalPoolingMode(),
                entity.getSafeModeOrigin(),
                entity.getSafeModeTrigger(),
                entity.getSafeModeReason(),
                entity.getSafeModeChangedAt(),
                entity.getVersion()
        );
    }
}
