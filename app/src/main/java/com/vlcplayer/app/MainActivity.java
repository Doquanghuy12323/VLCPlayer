// VLCPlayer MainActivity


    private void showHandyMainDialog() {
        android.content.SharedPreferences p = getSharedPreferences("handy_prefs", MODE_PRIVATE);
        String savedKey = p.getString("connection_key", "");
        String info = savedKey.isEmpty() ? "Chua co key" : "Key: " + savedKey.substring(0, Math.min(6, savedKey.length())) + "...";
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("Connection Key tu handyfeeling.com");
        input.setText(savedKey);
        input.setSingleLine(true);
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("The Handy").setMessage(info)
            .setPositiveButton("Nhap Key", (d, w) -> {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Connection Key").setView(input)
                    .setPositiveButton("Luu", (d2, w2) -> {
                        String k = input.getText().toString().trim();
                        if (!k.isEmpty()) { p.edit().putString("connection_key", k).apply();
                        android.widget.Toast.makeText(this, "Da luu key!", android.widget.Toast.LENGTH_SHORT).show(); }
                    }).setNegativeButton("Huy", null).show();
            }).setNegativeButton("Dong", null).show();
    }

}
