$cells = @(
  @{rate=1;   payloadSizeBytes=1024;     durationSec=900},
  @{rate=1;   payloadSizeBytes=102400;   durationSec=900},
  @{rate=1;   payloadSizeBytes=1048576;  durationSec=900},
  @{rate=1;   payloadSizeBytes=10485760; durationSec=900},
  @{rate=10;  payloadSizeBytes=1024;     durationSec=900},
  @{rate=10;  payloadSizeBytes=102400;   durationSec=900},
  @{rate=10;  payloadSizeBytes=1048576;  durationSec=900},
  @{rate=10;  payloadSizeBytes=10485760; durationSec=900},
  @{rate=100; payloadSizeBytes=1024;     durationSec=900},
  @{rate=100; payloadSizeBytes=102400;   durationSec=900},
  @{rate=100; payloadSizeBytes=1048576;  durationSec=900},
  @{rate=500; payloadSizeBytes=1024;     durationSec=900},
  @{rate=500; payloadSizeBytes=102400;   durationSec=900}
)

foreach ($c in $cells) {
    & "$PSScriptRoot\..\micewriter-hub-v1\skills\run-load-cell.ps1" -Rate $c.rate -PayloadBytes $c.payloadSizeBytes -DurationSec $c.durationSec -RestSec 60
}
