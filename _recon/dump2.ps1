$ErrorActionPreference = 'Continue'
$javap = 'C:\Program Files\Java\jdk-21\bin\javap.exe'
$recon = 'D:\DS_WORKSPACE\raildispatch\_recon'
$cre = Join-Path $recon 'create.jar'

$jobs = @(
  @{ n = 'cre_TravellingPoint';        c = 'com.simibubi.create.content.trains.entity.TravellingPoint' },
  @{ n = 'cre_Navigation';             c = 'com.simibubi.create.content.trains.entity.Navigation' },
  @{ n = 'cre_EdgeData';               c = 'com.simibubi.create.content.trains.graph.EdgeData' },
  @{ n = 'cre_SignalPropagator';       c = 'com.simibubi.create.content.trains.signal.SignalPropagator' },
  @{ n = 'cre_TrackTargetingBehaviour';c = 'com.simibubi.create.content.trains.track.TrackTargetingBehaviour' },
  @{ n = 'cre_TrackTargetingBlockItem';c = 'com.simibubi.create.content.trains.track.TrackTargetingBlockItem' },
  @{ n = 'cre_EdgePointManager';       c = 'com.simibubi.create.content.trains.graph.EdgePointManager' },
  @{ n = 'cre_EdgePointStorage';       c = 'com.simibubi.create.content.trains.graph.EdgePointStorage' },
  @{ n = 'cre_StationBlockEntity';     c = 'com.simibubi.create.content.trains.station.StationBlockEntity' },
  @{ n = 'cre_StationMarker';          c = 'com.simibubi.create.content.trains.station.StationMarker' },
  @{ n = 'cre_TrackObserver';          c = 'com.simibubi.create.content.trains.observer.TrackObserver' },
  @{ n = 'cre_BezierConnection';       c = 'com.simibubi.create.content.trains.track.BezierConnection' }
)

foreach ($j in $jobs) {
  $out = Join-Path $recon ($j.n + '.txt')
  $body = & $javap -p -c -constants -cp $cre $j.c 2>&1 | Out-String
  Set-Content -LiteralPath $out -Value $body -Encoding ASCII
  Write-Output ("{0,-32} {1,8} bytes" -f $j.n, $body.Length)
}
