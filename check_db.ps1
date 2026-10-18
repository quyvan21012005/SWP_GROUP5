$c = New-Object System.Data.SqlClient.SqlConnection('Server=localhost;Database=ctms_db;User Id=sa;Password=1111;TrustServerCertificate=True;')
$c.Open()
$cmd = $c.CreateCommand()
$cmd.CommandText = 'SELECT TOP 10 id, booking_number, container_number, appointment_type, status, notes FROM appointments ORDER BY id DESC'
$r = $cmd.ExecuteReader()
while ($r.Read()) {
    Write-Host "$($r['id']) - $($r['booking_number']) - $($r['container_number']) - $($r['status']) - $($r['notes'])"
}
$r.Close()

Write-Host "--- YARD SLOTS OCCUPIED ---"
$cmd2 = $c.CreateCommand()
$cmd2.CommandText = 'SELECT s.id, s.slot_code, s.block, c.container_number FROM yard_slots s JOIN containers c ON s.container_id = c.id'
$r2 = $cmd2.ExecuteReader()
while ($r2.Read()) {
    Write-Host "$($r2['slot_code']) ($($r2['block'])): $($r2['container_number'])"
}
$r2.Close()
$c.Close()
