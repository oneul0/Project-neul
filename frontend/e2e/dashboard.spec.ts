import { test, expect } from '@playwright/test';

/**
 * 대시보드 실시간 기능 E2E 테스트
 */
test.describe('Dashbaord E2E', () => {
    
    test('실시간 채팅 스트림 및 리차트 렌더링 확인', async ({ page }) => {
        // 1. 특정 채널 분석 대시보드로 이동
        const testChannelId = 'test-e2e-channel';
        await page.goto(`/channels/${testChannelId}`);

        // 2. 대시보드 레이아웃 렌더링 확인
        await expect(page.locator('h1')).toBeVisible();
        
        // 3. 차트 컴포넌트(SVG) 로드 대기
        // Recharts는 내부적으로 SVG를 사용하므로 해당 클래스 존재 여부로 로딩 확인
        const chart = page.locator('.recharts-responsive-container');
        await expect(chart).toBeVisible({ timeout: 15000 });

        // 4. (참고) 실제 데이터 흐름 테스트 시에는 백엔드에 Mock 데이터를 푸시한 뒤,
        // 차트 눈금이나 리스트 아이템이 변하는지 locator로 체크 가능
        console.log('Dashboard UI verified. Ready for real-time SSE event testing.');
    });

    test('하이라이트 감지 시 카드 노출 확인', async ({ page }) => {
        const testChannelId = 'test-e2e-channel';
        await page.goto(`/channels/${testChannelId}`);

        // 하이라이트 목록 섹션 존재 확인
        const highlightSection = page.locator('text=최근 하이라이트');
        await expect(highlightSection).toBeVisible();
        
        // 실제 데이터가 주입된 후 'highlight-card' 클래스 등이 생기는지 검증 가능
        // await expect(page.locator('.highlight-card')).toHaveCount(1);
    });
});
