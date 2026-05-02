import { test, expect } from '@playwright/test';

const TEST_CHANNEL_ID = 'test-e2e-channel';
const TEST_VIDEO_NO = '1234567890';

const mockMetadata = {
    videoNo: TEST_VIDEO_NO,
    channelId: TEST_CHANNEL_ID,
    title: '테스트 다시보기',
    publishDateAt: '2026-01-01T12:00:00',
    thumbnailImageUrl: null,
    duration: 7200,
    readCount: 1000,
    channelName: '테스트 채널',
};

const mockStatus = { status: 'IDLE' };
const mockHighlights: unknown[] = [];
const mockTimeline: unknown[] = [];

function setupVodMocks(page: Parameters<typeof test>[1] extends (args: { page: infer P }) => void ? P : never, videoNo = TEST_VIDEO_NO) {
    return Promise.all([
        page.route(`/api/vod/${videoNo}/metadata`, (route) =>
            route.fulfill({ json: mockMetadata })
        ),
        page.route(`/api/vod/${videoNo}/status`, (route) =>
            route.fulfill({ json: mockStatus })
        ),
        page.route(`/api/vod/${videoNo}/highlights`, (route) =>
            route.fulfill({ json: mockHighlights })
        ),
        page.route(`/api/vod/${videoNo}/timeline`, (route) =>
            route.fulfill({ json: mockTimeline })
        ),
        page.route('/api/me/vod-library', (route) =>
            route.fulfill({ json: [] })
        ),
        page.route('/api/me/vod-preferences', (route) =>
            route.fulfill({ json: null })
        ),
    ]);
}

test.describe('VOD 다시보기 보드 E2E', () => {

    test('다시보기 탭 전환 시 조회 섹션이 노출된다', async ({ page }) => {
        await page.goto(`/channels/${TEST_CHANNEL_ID}`);

        const vodTab = page.getByRole('button', { name: '다시보기' });
        await expect(vodTab).toBeVisible();
        await vodTab.click();

        await expect(
            page.getByText('다시보기를 찾고 편집 후보 검토를 시작하세요')
        ).toBeVisible();
    });

    test('VOD 번호 입력 후 조회 시 영상 제목이 표시된다', async ({ page }) => {
        await setupVodMocks(page);
        await page.goto(`/channels/${TEST_CHANNEL_ID}`);

        await page.getByRole('button', { name: '다시보기' }).click();

        const input = page.getByPlaceholder('VOD 번호 또는 전체 URL 붙여넣기');
        await expect(input).toBeVisible();
        await input.fill(TEST_VIDEO_NO);

        await page.getByRole('button', { name: '조회' }).click();

        await expect(page.getByText('테스트 다시보기')).toBeVisible({ timeout: 10000 });
    });

    test('조회 완료 후 분석 시작 버튼이 노출된다', async ({ page }) => {
        await setupVodMocks(page);
        await page.goto(`/channels/${TEST_CHANNEL_ID}`);

        await page.getByRole('button', { name: '다시보기' }).click();

        const input = page.getByPlaceholder('VOD 번호 또는 전체 URL 붙여넣기');
        await input.fill(TEST_VIDEO_NO);
        await page.getByRole('button', { name: '조회' }).click();

        await expect(page.getByRole('button', { name: /분석 시작/ })).toBeVisible({ timeout: 10000 });
    });
});
