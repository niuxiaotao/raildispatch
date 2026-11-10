$ErrorActionPreference = 'Continue'
$javap = 'C:\Program Files\Java\jdk-21\bin\javap.exe'
$recon = 'D:\DS_WORKSPACE\raildispatch\_recon'
$mods  = 'D:\DAOXIAN\.minecraft\versions\1.21.1-NeoForge_21.1.233\mods'

$cre = (Get-ChildItem -LiteralPath $mods -Filter '*create-1.21.1-6.0.10.jar').FullName
$rw  = (Get-ChildItem -LiteralPath $mods -Filter 'railways-*.jar').FullName

$jobs = @(
  @{ cp = $cre; n = 'nav_Navigation';        c = 'com.simibubi.create.content.trains.entity.Navigation' },
  @{ cp = $cre; n = 'nav_DiscoveredPath';    c = 'com.simibubi.create.content.trains.graph.DiscoveredPath' },
  @{ cp = $cre; n = 'nav_TravellingPoint';   c = 'com.simibubi.create.content.trains.entity.TravellingPoint' },
  @{ cp = $cre; n = 'nav_NavFrontierEntry';  c = 'com.simibubi.create.content.trains.entity.Navigation$FrontierEntry' },
  @{ cp = $cre; n = 'nav_NavStationTest';    c = 'com.simibubi.create.content.trains.entity.Navigation$StationTest' },
  @{ cp = $cre; n = 'nav_TrackGraph';        c = 'com.simibubi.create.content.trains.graph.TrackGraph' },
  @{ cp = $cre; n = 'nav_TrackEdge';         c = 'com.simibubi.create.content.trains.graph.TrackEdge' },
  @{ cp = $rw;  n = 'rw_TrackSwitchBlock';   c = 'com.railwayteam.railways.content.switches.TrackSwitchBlock' },
  @{ cp = $rw;  n = 'rw_TrackSwitchBlockEntity'; c = 'com.railwayteam.railways.content.switches.TrackSwitchBlockEntity' },
  @{ cp = $rw;  n = 'rw_TrackSwitch';        c = 'com.railwayteam.railways.content.switches.TrackSwitch' },
  @{ cp = $rw;  n = 'rw_SwitchStateBlock';   c = 'com.railwayteam.railways.content.switches.TrackSwitchBlock$SwitchState' },
  @{ cp = $rw;  n = 'rw_AutoMode';           c = 'com.railwayteam.railways.content.switches.TrackSwitchBlockEntity$AutoMode' },
  @{ cp = $rw;  n = 'rw_SwitchConstraint';   c = 'com.railwayteam.railways.content.switches.TrackSwitchBlock$SwitchConstraint' },
  @{ cp = $rw;  n = 'rw_ISwitchDisabledEdge';c = 'com.railwayteam.railways.mixin_interfaces.ISwitchDisabledEdge' },
  @{ cp = $rw;  n = 'rw_SwitchBlockImpl';    c = 'com.railwayteam.railways.content.switches.neoforge.TrackSwitchBlockImpl' }
)

foreach ($j in $jobs) {
  $out = Join-Path $recon ($j.n + '.txt')
  $body = & $javap -p -c -constants -cp $j.cp $j.c 2>&1 | Out-String
  Set-Content -LiteralPath $out -Value $body -Encoding ASCII
  Write-Output ("{0,-28} {1,9} chars -> {2}" -f $j.n, $body.Length, $out)
}
