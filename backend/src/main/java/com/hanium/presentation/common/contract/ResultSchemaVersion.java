package com.hanium.presentation.common.contract;

import java.util.Map;

/**
 * 저장된 최종 분석 결과 JSON의 계약 버전입니다.
 *
 * <p>버전 필드가 없던 기존 결과는 {@link #LEGACY}로 취급합니다. 새 필드를 추가할 때는 가능한
 * 한 같은 버전을 유지하고, 기존 필드의 의미·타입을 호환 불가능하게 바꿀 때만 CURRENT를 올립니다.</p>
 */
public final class ResultSchemaVersion {

    public static final String FIELD = "schemaVersion";
    public static final int LEGACY = 0;
    public static final int CURRENT = 2;

    private ResultSchemaVersion() {
    }

    public static int resolve(Map<String, Object> result) {
        if (result == null) {
            return LEGACY;
        }

        Object rawVersion = result.get(FIELD);
        if (rawVersion instanceof Number number) {
            return number.intValue();
        }

        return LEGACY;
    }
}
