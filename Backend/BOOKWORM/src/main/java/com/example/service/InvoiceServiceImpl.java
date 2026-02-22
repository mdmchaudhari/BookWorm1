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

            // ===== RENT =====
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
            // ===== LIBRARY (MEMBERSHIP) =====
            else if (item.getTranType() == 'L') {
                // Fetch active subscription to get package price for per-book cost
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
            // ===== PURCHASE =====
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

        // ===== TAX (can change later) =====
        BigDecimal tax = BigDecimal.ZERO; // or 0.18 * subtotal if GST
        BigDecimal grandTotal = subTotal.add(tax);

        InvoicePreviewResponseDTO response = new InvoicePreviewResponseDTO();
        response.setItems(previewItems);
        response.setSubTotal(subTotal);
        response.setTax(tax);
        response.setGrandTotal(grandTotal);

        return response;
    }

    // ===============================
    // Generate Invoice (P + R + L)
    // ===============================
    @Override
    @Transactional
    public InvoiceResponseDTO generateInvoice(Integer userId) {

        List<Cart> cartItems = cartRepository.findByCustomerUserId(userId);

        if (cartItems.isEmpty()) {
            throw new RuntimeException("Cart is empty. Cannot generate invoice.");
        }

        LocalDate today = LocalDate.now();
        BigDecimal totalAmount = BigDecimal.ZERO;

        // -------- INVOICE HEADER --------
        Invoice invoice = new Invoice();
        invoice.setUserId(userId);
        invoice.setInvoiceDate(today);
        invoice.setInvoiceAmount(BigDecimal.ZERO);
        invoice = invoiceRepository.save(invoice);

        List<InvoiceDetail> invoiceDetails = new ArrayList<>();

        // -------- PROCESS ITEMS --------
        for (Cart item : cartItems) {

            // Duplicate Check & Upgrade Logic
            List<MyShelf> existing = shelfRepository.findByUserIdAndProductProductId(userId,
                    item.getProduct().getProductId());
            boolean alreadyPurchased = existing.stream().anyMatch(s -> s.getTranType() == 'P');
            boolean activeRentOrLib = existing.stream()
                    .anyMatch(s -> (s.getTranType() == 'R' || s.getTranType() == 'L') &&
                            s.getProductExpiryDate() != null && !s.getProductExpiryDate().isBefore(today));

            // Case 1: Trying to Purchase (P)
            if (item.getTranType() == 'P') {
                if (alreadyPurchased) {
                    throw new RuntimeException(
                            "Product '" + item.getProduct().getProductName() + "' is already purchased.");
                }
                // Allow upgrade: If currently rented/library, remove the old entry so it can be
                // replaced by Purchase
                if (activeRentOrLib) {
                    // Find and delete the rented/library entry to "upgrade" it
                    List<MyShelf> toRemove = existing.stream()
                            .filter(s -> (s.getTranType() == 'R' || s.getTranType() == 'L'))
                            .toList();
                    shelfRepository.deleteAll(toRemove);
                }
            }
            // Case 2: Trying to Rent (R) or Library (L)
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

            // ===== RENT =====
            if (item.getTranType() == 'R') {

                BigDecimal perDay = product.getRentPerDay();
                int days = item.getRentDays();
                BigDecimal total = perDay.multiply(BigDecimal.valueOf(days));

                detail.setTranType(item.getTranType()); // 'R'
                detail.setQuantity(1);
                detail.setBasePrice(perDay); // per day rent
                detail.setSalePrice(total); // total rent
                detail.setRentNoOfDays(days);

                totalAmount = totalAmount.add(total);

                // Shelf expiry = today + rent days
                MyShelf shelf = new MyShelf();
                shelf.setUserId(item.getCustomer().getUserId());
                shelf.setProduct(product);
                shelf.setProductExpiryDate(today.plusDays(days));
                shelf.setTranType(item.getTranType());

                shelfRepository.save(shelf);

                // ✅ Generate Royalty for Rent
                royaltyTransactionService.generateRoyalty(item.getCustomer().getUserId(), product, 'R', total, perDay,
                        1, days, invoice);
            }
            // ===== LIBRARY (MEMBERSHIP) =====
            else if (item.getTranType() == 'L') {
                // Delegate to ShelfService to check subscription limits and add to library
                shelfService.lendFromSubscription(item.getCustomer().getUserId(),
                        product.getProductId(), invoice);

                // Fetch the subscription to calculate per-book value for invoice visibility
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
            // ===== PURCHASE =====
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

                // ✅ Generate Royalty for Purchase
                royaltyTransactionService.generateRoyalty(item.getCustomer().getUserId(), product, 'P', saleTotal,
                        price,
                        item.getQuantity(), 0, invoice);
            }

            invoiceDetailRepository.save(detail);
            invoiceDetails.add(detail);
        }

        // -------- FINALIZE --------
        invoice.setInvoiceAmount(totalAmount);
        invoiceRepository.save(invoice);

        byte[] pdfBytes = invoicePdfService.generateInvoicePdf(invoice, invoiceDetails);

        // Send Email with Invoice PDF
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

    // ===============================
    // Fetch Invoices
    // ===============================
    @Override
    public List<Invoice> getInvoicesByCustomer(Integer userId) {
        return invoiceRepository.findByUserId(userId);
    }

    @Override
    public Invoice getInvoiceById(Integer invoiceId) {
        return invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
    }

    // ===============================
    // Effective Price Helper
    // ===============================
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
