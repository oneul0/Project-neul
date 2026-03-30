# Chat Simulation Script for Project Neul
# Usage: .\simulate_chat.ps1 -roomId "test-room" -count 10

param (
    [string]$roomId = "test-room",
    [int]$count = 5,
    [string]$bootstrapServer = "localhost:9092"
)

$topic = "raw-chat-batch-topic"
$messages = @()

$contents = @(
    "오늘 방송 진짜 레전드네요 ㅋㅋㅋ",
    "와 대박이다 이거 실화냐??",
    "ㅋㅋㅋㅋㅋㅋㅋ 개웃겨",
    "아니 방금 뭐 지나감??",
    "ㄹㅇㅋㅋ 어흐~",
    "응원합니다!! 화이팅",
    "이거 진짜 성능 확실하구만",
    "대박사건...",
    "민심 체크중...",
    "!1",
    "!2"
)

$senders = @("김철수", "이영희", "박지민", "최민수", "정지환")

Write-Host "Generating $count mock messages for room: $roomId..." -ForegroundColor Cyan

for ($i = 0; $i -lt $count; $i++) {
    $msg = @{
        messageId = [Guid]::NewGuid().ToString()
        roomId = $roomId
        messageType = "CHAT"
        sender = $senders[($i % $senders.Length)]
        senderId = "user_$($i % 5)"
        content = $contents[(Get-Random -Maximum $contents.Length)]
        timestamp = [DateTime]::Now.ToString("yyyy-MM-ddTHH:mm:ss")
    }
    $messages += $msg
}

$batch = @{
    roomId = $roomId
    batchTime = [DateTime]::Now.ToString("yyyy-MM-ddTHH:mm:ss")
    messages = $messages
}

$json = $batch | ConvertTo-Json -Depth 10 -Compress

Write-Host "Sending batch to Kafka ($topic)..." -ForegroundColor Yellow

# Using docker exec to send message to kafka if local kafka-console-producer is not in PATH
# Assuming the kafka container is named 'kafka' as per typical docker-compose
if (Get-Command "docker" -ErrorAction SilentlyContinue) {
    echo $json | docker exec -i kafka /opt/bitnami/kafka/bin/kafka-console-producer.sh --bootstrap-server $bootstrapServer --topic $topic --property "parse.key=true" --property "key.separator=:" --property "key=$roomId"
} else {
    Write-Error "Docker not found. Please ensure Kafka is running and accessible."
}

Write-Host "Success! Check analyzer/core-api logs." -ForegroundColor Green
