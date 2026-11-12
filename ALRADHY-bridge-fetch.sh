#!/data/data/com.termux/files/usr/bin/bash
set -e

REPO="AbdullahSR007/ALRADHY-Universal-Bridge"
echo "Checking latest GitHub Actions run..."

if ! command -v gh &> /dev/null; then
    pkg install gh -y
fi

RUN_ID=$(gh run list --repo $REPO --limit 1 --json databaseId --jq '.[0].databaseId')
RUN_STATUS=$(gh run view $RUN_ID --repo $REPO --json status --jq '.status')
RUN_CONCLUSION=$(gh run view $RUN_ID --repo $REPO --json conclusion --jq '.conclusion')

echo "Run ID: $RUN_ID | Status: $RUN_STATUS | Result: $RUN_CONCLUSION"

if [ "$RUN_CONCLUSION" == "success" ]; then
    echo "Build successful! Downloading APK artifact..."
    rm -rf ./downloaded-apk
    mkdir -p ./downloaded-apk
    gh run download $RUN_ID --repo $REPO --dir ./downloaded-apk
    
    APK_FILE=$(find ./downloaded-apk -name "*.apk" | head -n 1)
    
    if [ -f "$APK_FILE" ]; then
        mkdir -p /sdcard/Download
        cp -f "$APK_FILE" /sdcard/Download/ALRADHY-Universal-Bridge.apk
        echo "SUCCESS: APK saved to /sdcard/Download/ALRADHY-Universal-Bridge.apk"
    else
        echo "Error: APK file not found!"
        find ./downloaded-apk
    fi
    
elif [ "$RUN_CONCLUSION" == "failure" ]; then
    echo "Build failed! Fetching logs..."
    gh run view $RUN_ID --repo $REPO --log-failed > build_error_log.txt
    tail -n 40 build_error_log.txt
else
    echo "Build is still in progress ($RUN_STATUS). Please wait."
fi
