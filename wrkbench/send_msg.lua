function getRandom(n)                                                                                                                                                                 
    local t = {                                                                                                                                                                       
        "0","1","2","3","4","5","6","7","8","9",                                                                                                                                      
        "a","b","c","d","e","f","g","h","i","j","k","l","m","n","o","p","q","r","s","t","u","v","w","x","y","z",                                                                      
        "A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z",                                                                      
    }                                                                                                                                                                                 
    local s = ""                                                                                                                                                                      
    for i =1, n do                                                                                                                                                                    
        s = s .. t[math.random(#t)]                                                                                                                                                   
    end;                                                                                                                                                                              
    return s                                                                                                                                                                          
end;                                                                                                                                                                                  
                                                                                                                                                                                      
wrk.method = "POST"                                                                                                                                                                   
wrk.body = '{"to":"demo@example.com","subject":"发货通知","templateId":"<templateId>","templateData":{"user_name":"老虎","order_id":"12345678"},"priority":3}'
wrk.headers["Content-Type"] = "application/json"                                                                                                                                      
                                                                                                                                                                                      
function request()                                                                                                                                                                    
        wrk.headers["Trace-ID"] = getRandom(32)                                                                                                                                       
        wrk.headers["Source-ID"] = "abcdeftest"                                                                                                                                       
        return wrk.format('POST', nil, headers, body)                                                                                                                                 
end  