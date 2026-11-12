#!/data/data/com.termux/files/usr/bin/bash
set -e

REPO="AbdullahSR007/ALRADHY-Universal-Bridge"
echo "🔍 [ALRADHY-Debug Engine] Checking latest GitHub Actions run for $REPO..."

if ! command -v gh &> /dev/null; then
    echo "⚙️ Installing GitHub CLI..."
    pkg install gh -y
fi

RUN_ID=$(gh run list --repo $REPO --limit 1 --json databaseId --jq '.[0].databaseId')
RUN_STATUS=$(gh run view $RUN_ID --repo $REPO --json status --jq '.status')
RUN_CONCLUSION=$(gh run view $RUN_ID --repo $REPO --json conclusion --jq '.conclusion')

echo "📌 Run ID: $RUN_ID | Status: $RUN_STATUS | Result: $RUN_CONCLUSION"

if [ "$RUN_CONCLUSION" == "success" ]; then
    echo "✅ Build successful! Downloading APK artifact..."
    gh run download $RUN_ID --repo $REPO --dir ./downloaded-apk
    
    mkdir -p /sdcard/Download
    cp -f ./downloaded-apk/**/*.apk /sdcard/Download/ALRADHY-Universal-Bridge.apk
    echo "🎉 APK successfully saved to: /sdcard/Download/ALRADHY-Universal-Bridge.apk"
    
elif [ "$RUN_CONCLUSION" == "failure" ]; then
    echo "❌ Build failed! Fetching error logs..."
    gh run view $RUN_ID --repo $REPO --log-failed > build_error_log.txt
    tail -n 30 build_error_log.txt
else
    echo "⏳ Build is still in progress ($RUN_STATUS). Please wait a moment and run the script again."
fi
