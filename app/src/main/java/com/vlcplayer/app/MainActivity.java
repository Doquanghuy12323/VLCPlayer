
    private void showHandyMainDialog() {
        android.content.SharedPreferences p = getSharedPreferences("handy_prefs", MODE_PRIVATE);
        String k = p.getString("connection_key", "");
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("The Handy")
            .setMessage(k.isEmpty() ? "Chua co Connection Key.\nLay key tai: handyfeeling.com/setup" : "Key hien tai: " + k.substring(0, Math.min(8,k.length())) + "...\nMo video roi bam nut Handy de ket noi.")
            .setPositiveButton("Nhap Key", (d,w) -> {
                android.widget.EditText et = new android.widget.EditText(this);
                et.setHint("Connection Key");
                et.setText(k);
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Nhap Connection Key")
                    .setView(et)
                    .setPositiveButton("Luu", (d2,w2) -> {
                        String nk = et.getText().toString().trim();
                        if (!nk.isEmpty()) {
                            p.edit().putString("connection_key", nk).apply();
                            android.widget.Toast.makeText(this, "Da luu key thanh cong!", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Huy", null).show();
            })
            .setNegativeButton("Dong", null).show();
    }
}

    private void showHandyMainDialog() {
        android.content.SharedPreferences p = getSharedPreferences("handy_prefs", MODE_PRIVATE);
        String k = p.getString("connection_key", "");
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("The Handy")
            .setMessage(k.isEmpty() ? "Chua co Connection Key.\nLay key tai: handyfeeling.com/setup" : "Key hien tai: " + k.substring(0, Math.min(8,k.length())) + "...\nMo video roi bam nut Handy de ket noi.")
            .setPositiveButton("Nhap Key", (d,w) -> {
                android.widget.EditText et = new android.widget.EditText(this);
                et.setHint("Connection Key");
                et.setText(k);
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Nhap Connection Key")
                    .setView(et)
                    .setPositiveButton("Luu", (d2,w2) -> {
                        String nk = et.getText().toString().trim();
                        if (!nk.isEmpty()) {
                            p.edit().putString("connection_key", nk).apply();
                            android.widget.Toast.makeText(this, "Da luu key thanh cong!", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Huy", null).show();
            })
            .setNegativeButton("Dong", null).show();
    }
}
