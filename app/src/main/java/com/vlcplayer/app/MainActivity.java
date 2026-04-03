
    private void showHandyMainDialog() {
        android.content.SharedPreferences p = getSharedPreferences("handy_prefs", MODE_PRIVATE);
        String k = p.getString("connection_key", "");
        String msg = k.isEmpty() ? "Chua co key. Lay tai: handyfeeling.com/setup" : "Key: " + k.substring(0, Math.min(8,k.length())) + "...";
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("Connection Key"); et.setText(k); et.setSingleLine(true);
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("The Handy").setMessage(msg).setPositiveButton("Nhap Key", (d,w) -> new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Connection Key").setView(et).setPositiveButton("Luu", (d2,w2) -> { String nk=et.getText().toString().trim(); if(!nk.isEmpty()){p.edit().putString("connection_key",nk).apply(); android.widget.Toast.makeText(this,"Da luu!",android.widget.Toast.LENGTH_SHORT).show();}}).setNegativeButton("Huy",null).show()).setNegativeButton("Dong",null).show();
    }

}