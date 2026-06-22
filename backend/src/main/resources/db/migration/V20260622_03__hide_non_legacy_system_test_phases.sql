with official_phases(testing_phase) as (
    values
        ('CC2024R3第一轮系统测试'),
        ('CC2024R3第二轮系统测试'),
        ('CC2024R3第三轮系统测试'),
        ('CC2024R3回归测试'),
        ('CC2024R2第一轮系统测试'),
        ('CC2024R2第二轮系统测试'),
        ('CC2024R2第三轮系统测试'),
        ('CC2024R2回归测试'),
        ('CC2024R4第一轮系统测试'),
        ('CC2024R4第二轮系统测试'),
        ('CC2024R4第三轮系统测试'),
        ('CC2024R4回归测试'),
        ('CC2025R1第一轮系统测试'),
        ('CC2025R1第二轮系统测试'),
        ('CC2025R1第三轮系统测试'),
        ('CC2025R1回归测试'),
        ('CC2025R2第一轮系统测试'),
        ('CC2025R2第二轮系统测试'),
        ('CC2025R2第三轮系统测试'),
        ('CC2025R2回归测试'),
        ('CC2025R3第一轮系统测试'),
        ('CC2025R3第二轮系统测试'),
        ('CC2025R3第三轮系统测试'),
        ('CC2025R3回归测试'),
        ('CC2025R4第一轮系统测试'),
        ('CC2025R4第二轮系统测试'),
        ('CC2025R4第三轮系统测试'),
        ('CC2025R4回归测试'),
        ('CC2026R1第一轮系统测试'),
        ('CC2026R1第二轮系统测试'),
        ('CC2026R1第三轮系统测试'),
        ('CC2026R1回归测试'),
        ('CC2026R2第一轮系统测试'),
        ('CC2026R2第二轮系统测试'),
        ('CC2026R2第三轮系统测试'),
        ('CC2026R2回归测试'),
        ('CC2026R3第一轮系统测试'),
        ('CC2026R3第二轮系统测试'),
        ('CC2026R3第三轮系统测试'),
        ('CC2026R3第四轮系统测试'),
        ('CC2026R3第五轮系统测试'),
        ('CC2026R3第六轮系统测试'),
        ('CC2026R3回归测试'),
        ('CC2026R4第一轮系统测试'),
        ('CC2026R4第二轮系统测试'),
        ('CC2026R4第三轮系统测试'),
        ('CC2026R4第四轮系统测试'),
        ('CC2026R4第五轮系统测试'),
        ('CC2026R4第六轮系统测试'),
        ('CC2026R4回归测试')
)
update testing_phase_calendar c
   set enabled = false,
       remark = coalesce(nullif(c.remark, ''), 'hidden by legacy testing_phase.csv scope migration'),
       updated_at = current_timestamp
 where c.project_id = 9
   and not exists (
       select 1
         from official_phases p
        where p.testing_phase = c.testing_phase
   );
