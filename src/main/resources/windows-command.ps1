$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\Modules\CimCmdlets\CimCmdlets.psd1')
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class UpdaterCommandLine {
    [DllImport("shell32.dll", SetLastError=true)]
    static extern IntPtr CommandLineToArgvW([MarshalAs(UnmanagedType.LPWStr)] string command, out int count);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr ptr);
    public static string[] Split(string command) {
        int count;
        IntPtr ptr = CommandLineToArgvW(command, out count);
        if (ptr == IntPtr.Zero) throw new InvalidOperationException("Command line unavailable");
        try {
            var result = new string[count];
            for (int i=0; i<count; i++) result[i]=Marshal.PtrToStringUni(Marshal.ReadIntPtr(ptr, i*IntPtr.Size));
            return result;
        } finally { LocalFree(ptr); }
    }
}
'@
$own = Get-CimInstance -ClassName Win32_Process -Filter 'ProcessId = __PROCESS_ID__'
if (-not $own.CommandLine) { throw 'Command line unavailable' }
ConvertTo-Json -InputObject ([UpdaterCommandLine]::Split($own.CommandLine)) -Compress
