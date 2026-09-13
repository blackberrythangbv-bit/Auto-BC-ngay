# KPI → Tammi V1 CLEAN

Bản sạch để build APK bằng GitHub Actions.

## Build
1. Upload toàn bộ nội dung thư mục này vào root repo.
2. Xóa workflow cũ trong `.github/workflows/` nếu có, chỉ giữ `main.yml`.
3. GitHub > Actions > Build KPI Tammi APK > Run workflow.
4. Khi xanh, tải Artifact `KPI-Tammi-AutoShare-V1-APK`.

## Luồng app
- Lưu URL ZIP.
- Chạy tự động hàng ngày (mặc định 07:30).
- Tải ZIP, kiểm tra chữ ký ZIP, giải nén an toàn.
- Lọc file báo cáo hợp lệ.
- Thông báo và 1 chạm mở Share Sheet để gửi Tammi.
