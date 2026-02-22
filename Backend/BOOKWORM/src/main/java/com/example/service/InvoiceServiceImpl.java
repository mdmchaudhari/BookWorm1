package com.example.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.example.model.UserLibrarySubscription;

import com.example.model.Cart;
import com.example.model.Invoice;
import com.example.model.InvoiceDetail;
import com.example.model.InvoicePreviewItemDTO;
import com.example.model.InvoicePreviewResponseDTO;
import com.example.model.InvoiceResponseDTO;
import com.example.model.MyShelf;
import com.example.model.Product;
import com.example.repository.CartRepository;
import com.example.repository.InvoiceDetailRepository;
import com.example.repository.InvoiceRepository;
import com.example.repository.ShelfRepository;
import com.example.repository.CustomerRepository;
import com.example.model.Customer;

import jakarta.transaction.Transactional;

@Service
public class InvoiceServiceImpl implements IInvoiceService {

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceDetailRepository invoiceDetailRepository;

    @Autowired
    private CartRepository cartRepository;

    @Autowired
    private ShelfRepository shelfRepository;

    @Autowired
    private InvoicePdfService invoicePdfService;

    @Autowired
    private ShelfService shelfService;

    @Autowired
    private RoyaltyTransactionService royaltyTransactionService;

    @Autowired
    private EmailService emailService;

    @Autowired
    private CustomerRepository customerRepository;

    
    @Override
    public InvoicePreviewResponseDTO previewInvoice(Integer userId) {

        List<Cart> cartItems = cartRepository.findByCustomerUserId(userId);

        System.out.println("Processing previewInvoice for userId: " + userId);
        System.out.println("Retrieved cart items size: " + (cartItems != null ? cartItems.size() : "null"));

        if (cartItems.isEmpty()) {
            System.out.println("Cart is empty for user " + userId + ". Dumping all cart items:");
            cartRepository.findAll().forEach(c -> System.out.println("Cart ID: " + c.getCartId() + ", Customer ID: "
                    + c.getCustomer().getUserId() + ", Product ID: " + c.getProduct().getProductId()));
            throw new RuntimeException("Cart is empty");
        }

        List<InvoicePreviewItemDTO> previewItems = new ArrayList<>();
        BigDecimal subTotal = BigDecimal.ZERO;

        for (Cart item : cartItems) {

            Product product = item.getProduct();
            InvoicePreviewItemDTO dto = new InvoicePreviewItemDTO();

            dto.setProductId(product.getProductId());
            dto.setProductName(product.getProductName());
            dto.setTranType(item.getTranType());

            
            if (item.getTranType() == 'R') {

                BigDecimal perDay = product.getRentPerDay();
                int days = item.getRentDays();
                BigDecimal total = perDay.multiply(BigDecimal.valueOf(days));

                dto.setQuantity(1);
                dto.setRentDays(days);
                dto.setUnitPrice(perDay);
                dto.setTotalPrice(total);

                subTotal = subTotal.add(total);
            }
           
            else if (item.getTranType() == 'L') {
                
                UserLibrarySubscription sub = shelfRepository.findSubscriptionByUserId(userId);
                BigDecimal perBookValue = BigDecimal.ZERO;

                if (sub != null && sub.getLibraryPackage() != null) {
                    BigDecimal packagePrice = sub.getLibraryPackage().getPrice();
                    int maxBooks = sub.getLibraryPackage().getMaxSelectableBooks();
                    if (maxBooks > 0) {
                        perBookValue = packagePrice.divide(BigDecimal.valueOf(maxBooks), 2,
                                java.math.RoundingMode.HALF_UP);
                    }
                }

                dto.setQuantity(1);
                dto.setRentDays(0);
                dto.setUnitPrice(perBookValue);
                dto.setTotalPrice(perBookValue);

                subTotal = subTotal.add(perBookValue);
            }
            
            else {
                BigDecimal price = getEffectivePrice(product);
                BigDecimal total = price.multiply(BigDecimal.valueOf(item.getQuantity()));

                dto.setQuantity(item.getQuantity());
                dto.setRentDays(0);
                dto.setUnitPrice(price);
                dto.setTotalPrice(total);

                subTotal = subTotal.add(total);
            }

            previewItems.add(dto);
        }

        
        BigDecimal tax = BigDecimal.ZERO; 
        BigDecimal grandTotal = subTotal.add(tax);

        InvoicePreviewResponseDTO response = new InvoicePreviewResponseDTO();
        response.setItems(previewItems);
        response.setSubTotal(subTotal);
        response.setTax(tax);
        response.setGrandTotal(grandTotal);

        return response;
    }

   
    @Override
    @Transactional
    public InvoiceResponseDTO generateInvoice(Integer userId) {

        List<Cart> cartItems = cartRepository.findByCustomerUserId(userId);

        if (cartItems.isEmpty()) {
            throw new RuntimeException("Cart is empty. Cannot generate invoice.");
        }

        LocalDate today = LocalDate.now();
        BigDecimal totalAmount = BigDecimal.ZERO;

        Invoice invoice = new Invoice();
        invoice.setUserId(userId);
        invoice.setInvoiceDate(today);
        invoice.setInvoiceAmount(BigDecimal.ZERO);
        invoice = invoiceRepository.save(invoice);

        List<InvoiceDetail> invoiceDetails = new ArrayList<>();

       
        for (Cart item : cartItems) {

           
            List<MyShelf> existing = shelfRepository.findByUserIdAndProductProductId(userId,
                    item.getProduct().getProductId());
            boolean alreadyPurchased = existing.stream().anyMatch(s -> s.getTranType() == 'P');
            boolean activeRentOrLib = existing.stream()
                    .anyMatch(s -> (s.getTranType() == 'R' || s.getTranType() == 'L') &&
                            s.getProductExpiryDate() != null && !s.getProductExpiryDate().isBefore(today));

            
            if (item.getTranType() == 'P') {
                if (alreadyPurchased) {
                    throw new RuntimeException(
                            "Product '" + item.getProduct().getProductName() + "' is already purchased.");
                }
                
                if (activeRentOrLib) {
                    
                    List<MyShelf> toRemove = existing.stream()
                            .filter(s -> (s.getTranType() == 'R' || s.getTranType() == 'L'))
                            .toList();
                    shelfRepository.deleteAll(toRemove);
                }
            }
            
            else {
                if (alreadyPurchased || activeRentOrLib) {
                    throw new RuntimeException(
                            "Product '" + item.getProduct().getProductName() + "' is already in your library/shelf.");
                }
            }

            Product product = item.getProduct();
            InvoiceDetail detail = new InvoiceDetail();

            detail.setInvoice(invoice);
            detail.setProduct(product);
            detail.setProductName(
                    product.getProductName() != null
                            ? product.getProductName()
                            : product.getProductEnglishName());

            
            if (item.getTranType() == 'R') {

                BigDecimal perDay = product.getRentPerDay();
                int days = item.getRentDays();
                BigDecimal total = perDay.multiply(BigDecimal.valueOf(days));

                detail.setTranType(item.getTranType()); 
                detail.setQuantity(1);
                detail.setBasePrice(perDay); 
                detail.setSalePrice(total); 
                detail.setRentNoOfDays(days);

                totalAmount = totalAmount.add(total);

                
                MyShelf shelf = new MyShelf();
                shelf.setUserId(item.getCustomer().getUserId());
                shelf.setProduct(product);
                shelf.setProductExpiryDate(today.plusDays(days));
                shelf.setTranType(item.getTranType());

                shelfRepository.save(shelf);

                
                royaltyTransactionService.generateRoyalty(item.getCustomer().getUserId(), product, 'R', total, perDay,
                        1, days, invoice);
            }
           
            else if (item.getTranType() == 'L') {
                
                shelfService.lendFromSubscription(item.getCustomer().getUserId(),
                        product.getProductId(), invoice);

                
                UserLibrarySubscription sub = shelfRepository.findSubscriptionByUserId(userId);
                BigDecimal perBookValue = BigDecimal.ZERO;

                if (sub != null && sub.getLibraryPackage() != null) {
                    BigDecimal packagePrice = sub.getLibraryPackage().getPrice();
                    int maxBooks = sub.getLibraryPackage().getMaxSelectableBooks();
                    if (maxBooks > 0) {
                        perBookValue = packagePrice.divide(BigDecimal.valueOf(maxBooks), 2,
                                java.math.RoundingMode.HALF_UP);
                    }
                }

                detail.setTranType('L');
                detail.setQuantity(1);
                detail.setBasePrice(perBookValue);
                detail.setSalePrice(perBookValue);
                detail.setRentNoOfDays(0);

                totalAmount = totalAmount.add(perBookValue);
            }
            
            else {

                BigDecimal price = getEffectivePrice(product);
                BigDecimal saleTotal = price.multiply(BigDecimal.valueOf(item.getQuantity()));

                detail.setTranType('P');
                detail.setQuantity(item.getQuantity());
                detail.setBasePrice(price);
                detail.setSalePrice(saleTotal);
                detail.setRentNoOfDays(0);

                totalAmount = totalAmount.add(saleTotal);

                MyShelf shelf = new MyShelf();
                shelf.setUserId(item.getCustomer().getUserId());
                shelf.setProduct(product);
                shelf.setProductExpiryDate(null);
                shelf.setTranType('P');
                shelfRepository.save(shelf);

                
                royaltyTransactionService.generateRoyalty(item.getCustomer().getUserId(), product, 'P', saleTotal,
                        price,
                        item.getQuantity(), 0, invoice);
            }

            invoiceDetailRepository.save(detail);
            invoiceDetails.add(detail);
        }

        
        invoice.setInvoiceAmount(totalAmount);
        invoiceRepository.save(invoice);

        byte[] pdfBytes = invoicePdfService.generateInvoicePdf(invoice, invoiceDetails);

       
        try {
            Customer customer = customerRepository.findById(userId).orElse(null);
            if (customer != null) {
                emailService.sendInvoiceEmail(customer, invoice, pdfBytes);
            }
        } catch (Exception e) {
            System.err.println("Email sending failed but order completed: " + e.getMessage());
        }

        cartRepository.deleteByCustomerUserId(userId);

        InvoiceResponseDTO response = new InvoiceResponseDTO();
        response.setInvoice(invoice);
        response.setInvoiceDetails(invoiceDetails);

        return response;
    }

   
    @Override
    public List<Invoice> getInvoicesByCustomer(Integer userId) {
        return invoiceRepository.findByUserId(userId);
    }

    @Override
    public Invoice getInvoiceById(Integer invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
    }

  
    private BigDecimal getEffectivePrice(Product product) {

        LocalDate today = LocalDate.now();

        if (product.getOfferPrice() != null
                && product.getOfferExpiryDate() != null
                && !product.getOfferExpiryDate().isBefore(today)) {

            return product.getOfferPrice();
        }

        return product.getSpCost();
    }
}
