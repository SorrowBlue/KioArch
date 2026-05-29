const path = require('path');

config.set({
    files: [
        // テストコードの実行よりも絶対に「先」に Wasm JS ラッパーをロードする！
        { 
            pattern: path.resolve(__dirname, 'kotlin/natives/kioarch.js'), 
            served: true, 
            included: true,  
            watched: false, 
            nocache: true 
        },
        ...config.files,
        { 
            pattern: path.resolve(__dirname, 'kotlin/natives/kioarch.wasm'), 
            served: true, 
            included: false, 
            watched: false, 
            nocache: true 
        },
        { 
            pattern: path.resolve(__dirname, 'kotlin/**/*'), 
            served: true, 
            included: false, 
            watched: false, 
            nocache: true 
        }
    ]
});
