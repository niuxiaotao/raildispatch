$ErrorActionPreference = 'Continue'
$javap = 'C:\Program Files\Java\jdk-21\bin\javap.exe'
$recon = 'D:\DS_WORKSPACE\raildispatch\_recon'
$sim = Join-Path $recon 'simurail.jar'
$sab = Join-Path $recon 'sable.jar'
$cre = Join-Path $recon 'create.jar'

$jobs = @(
  @{ cp = $sim; n = 'sim_PhysicsBogeyBlockEntity';   c = 'com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity' },
  @{ cp = $sim; n = 'sim_PhysicsBogeyProbeData';     c = 'com.crystaelix.simurail.content.bogey.PhysicsBogeyProbeData' },
  @{ cp = $sim; n = 'sim_PhysicsBogeyPeripheral';    c = 'com.crystaelix.simurail.compat.computercraft.peripheral.PhysicsBogeyPeripheral' },
  @{ cp = $sim; n = 'sim_ProbeReaderPeripheral';     c = 'com.crystaelix.simurail.compat.computercraft.peripheral.ProbeReaderPeripheral' },
  @{ cp = $sim; n = 'sim_TrackSegment';              c = 'com.crystaelix.simurail.content.track.TrackSegment' },
  @{ cp = $sim; n = 'sim_TrackSegmentFinder';        c = 'com.crystaelix.simurail.content.track.TrackSegmentFinder' },
  @{ cp = $sim; n = 'sim_TrackSegmentHelper';        c = 'com.crystaelix.simurail.content.track.TrackSegmentHelper' },
  @{ cp = $sim; n = 'sim_SubLevelUtil';              c = 'com.crystaelix.simurail.api.util.SubLevelUtil' },
  @{ cp = $sim; n = 'sim_SimurailEdgePoints';        c = 'com.crystaelix.simurail.content.SimurailEdgePoints' },
  @{ cp = $sim; n = 'sim_TravellingPointMixin';      c = 'com.crystaelix.simurail.mixin.TravellingPointMixin' },
  @{ cp = $sim; n = 'sim_SubLevelAuxPhysicsBody';    c = 'com.crystaelix.simurail.api.physics.SubLevelAuxiliaryPhysicsBody' },
  @{ cp = $sim; n = 'sim_TrackSwitchState';          c = 'com.crystaelix.simurail.content.track_switch.TrackSwitchState' },
  @{ cp = $sim; n = 'sim_ProbeReaderMode';           c = 'com.crystaelix.simurail.content.probe_reader.ProbeReaderMode' },
  @{ cp = $sim; n = 'sim_PhysicsBogeyPivotBE';       c = 'com.crystaelix.simurail.content.bogey.PhysicsBogeyPivotBlockEntity' },
  @{ cp = $sab; n = 'sab_SubLevel';                  c = 'dev.ryanhcode.sable.sublevel.SubLevel' },
  @{ cp = $sab; n = 'sab_ServerSubLevel';            c = 'dev.ryanhcode.sable.sublevel.ServerSubLevel' },
  @{ cp = $sab; n = 'sab_ServerSubLevelContainer';   c = 'dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer' },
  @{ cp = $sab; n = 'sab_SubLevelPhysicsSystem';     c = 'dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem' },
  @{ cp = $sab; n = 'sab_SubLevelContainer';         c = 'dev.ryanhcode.sable.api.sublevel.SubLevelContainer' },
  @{ cp = $cre; n = 'cre_TrackEdgePoint';            c = 'com.simibubi.create.content.trains.signal.TrackEdgePoint' },
  @{ cp = $cre; n = 'cre_TrackEdge';                 c = 'com.simibubi.create.content.trains.graph.TrackEdge' },
  @{ cp = $cre; n = 'cre_SignalBoundary';            c = 'com.simibubi.create.content.trains.signal.SignalBoundary' },
  @{ cp = $cre; n = 'cre_GlobalStation';             c = 'com.simibubi.create.content.trains.station.GlobalStation' },
  @{ cp = $cre; n = 'cre_SingleBlockEntityEdgePoint';c = 'com.simibubi.create.content.trains.signal.SingleBlockEntityEdgePoint' },
  @{ cp = $cre; n = 'cre_SignalBlock';               c = 'com.simibubi.create.content.trains.signal.SignalBlock' },
  @{ cp = $cre; n = 'cre_SignalBlockEntity';         c = 'com.simibubi.create.content.trains.signal.SignalBlockEntity' },
  @{ cp = $cre; n = 'cre_StationBlock';              c = 'com.simibubi.create.content.trains.station.StationBlock' },
  @{ cp = $cre; n = 'cre_TrackNode';                 c = 'com.simibubi.create.content.trains.graph.TrackNode' },
  @{ cp = $cre; n = 'cre_TrackGraph';                c = 'com.simibubi.create.content.trains.graph.TrackGraph' }
)

foreach ($j in $jobs) {
  $out = Join-Path $recon ($j.n + '.txt')
  $body = & $javap -p -c -constants -cp $j.cp $j.c 2>&1 | Out-String
  Set-Content -LiteralPath $out -Value $body -Encoding ASCII
  $len = $body.Length
  Write-Output ("{0,-32} {1,8} bytes  -> {2}" -f $j.n, $len, $out)
}
