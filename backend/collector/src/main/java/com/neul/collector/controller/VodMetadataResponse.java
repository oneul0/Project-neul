package com.neul.collector.controller;

public record VodMetadataResponse(
        boolean exists,
        String videoNo,
        String title,
        String thumbnailImageUrl,
        String publishDate,
        Long publishDateAt,
        String channelName,
        Integer duration,
        String category,
        String message
) {
    public static VodMetadataResponse notFound(String videoNo) {
        return new VodMetadataResponse(
                false,
                videoNo,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "해당 VOD를 찾을 수 없습니다."
        );
    }
}
